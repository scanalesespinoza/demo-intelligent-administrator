package com.iadmin.k8s.impl;

import com.iadmin.k8s.K8sConnector;
import com.iadmin.model.DeploymentSummary;
import com.iadmin.model.K8sEvent;
import com.iadmin.model.LogChunk;
import com.iadmin.model.PodSummary;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.events.v1.EventSeries;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class DefaultK8sConnector implements K8sConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultK8sConnector.class);

    private final KubernetesClient client;

    @Inject
    public DefaultK8sConnector(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public List<DeploymentSummary> listDeployments(String namespace) {
        var deploymentList = (namespace == null || namespace.isBlank())
                ? client.apps().deployments().inAnyNamespace().list()
                : client.apps().deployments().inNamespace(namespace).list();
        return Optional.ofNullable(deploymentList)
                .map(list -> list.getItems())
                .orElse(List.of())
                .stream()
                .map(deployment -> new DeploymentSummary(
                        deployment.getMetadata().getName(),
                        deployment.getMetadata().getNamespace(),
                        deployment.getSpec() != null ? deployment.getSpec().getReplicas() : null,
                        deployment.getStatus() != null ? deployment.getStatus().getAvailableReplicas() : null,
                        safeMap(deployment.getMetadata().getLabels()),
                        safeMap(deployment.getMetadata().getAnnotations())))
                .sorted(Comparator.comparing(DeploymentSummary::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Override
    public List<PodSummary> listPods(String namespace, Map<String, String> selector) {
        List<Pod> pods;
        if (namespace == null || namespace.isBlank()) {
            var op = client.pods().inAnyNamespace();
            var target = (selector != null && !selector.isEmpty()) ? op.withLabels(selector) : op;
            pods = Optional.ofNullable(target.list())
                    .map(list -> list.getItems())
                    .orElse(List.of());
        } else {
            var op = client.pods().inNamespace(namespace);
            var target = (selector != null && !selector.isEmpty()) ? op.withLabels(selector) : op;
            pods = Optional.ofNullable(target.list())
                    .map(list -> list.getItems())
                    .orElse(List.of());
        }
        return pods.stream()
                .map(pod -> {
                    var statuses = Optional.ofNullable(pod.getStatus())
                            .map(status -> status.getContainerStatuses())
                            .orElse(List.of());
                    int restarts = statuses.stream()
                            .map(ContainerStatus::getRestartCount)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .sum();
                    var containerStates = statuses.stream()
                            .map(status -> new PodSummary.ContainerState(
                                    status.getName(),
                                    Boolean.TRUE.equals(status.getReady())))
                            .toList();
                    String phase = Optional.ofNullable(pod.getStatus()).map(status -> status.getPhase()).orElse("Unknown");
                    return new PodSummary(
                            pod.getMetadata().getName(),
                            pod.getMetadata().getNamespace(),
                            phase,
                            restarts,
                            containerStates);
                })
                .sorted(Comparator.comparing(PodSummary::name))
                .toList();
    }

    @Override
    public List<K8sEvent> getEvents(String namespace, Instant from, Instant to) {
        var eventsOperation = client.events().v1().events();
        var eventList = (namespace == null || namespace.isBlank())
                ? eventsOperation.inAnyNamespace().list()
                : eventsOperation.inNamespace(namespace).list();
        return Optional.ofNullable(eventList)
                .map(list -> list.getItems())
                .orElse(List.of())
                .stream()
                .map(this::toEvent)
                .flatMap(Optional::stream)
                .filter(event -> isWithin(event.timestamp(), from, to))
                .sorted(Comparator.comparing(K8sEvent::timestamp))
                .toList();
    }

    @Override
    public List<LogChunk> getPodLogs(String namespace, String pod, String container,
                                     Instant from, Instant to, int tailLines) {
        if (namespace == null || namespace.isBlank()) {
            return List.of();
        }
        var podResource = client.pods().inNamespace(namespace).withName(pod);
        if (podResource == null) {
            return List.of();
        }
        String targetContainer = container;
        if (targetContainer == null || targetContainer.isBlank()) {
            targetContainer = Optional.ofNullable(podResource.get())
                    .map(Pod::getSpec)
                    .map(spec -> spec.getContainers())
                    .filter(containers -> !containers.isEmpty())
                    .map(containers -> containers.getFirst().getName())
                    .orElse(null);
        }
        if (targetContainer == null) {
            return List.of();
        }
        try {
            var containerResource = podResource.inContainer(targetContainer);
            PrettyLoggable loggable = containerResource;
            if (from != null) {
                loggable = ((TimeTailPrettyLoggable) loggable).sinceTime(from.toString());
            }
            if (tailLines > 0) {
                loggable = ((TailPrettyLoggable) loggable).tailingLines(tailLines);
            }
            String rawLog = loggable.getLog();
            List<String> lines = rawLog == null || rawLog.isBlank()
                    ? List.of()
                    : Arrays.stream(rawLog.split("\\R"))
                            .map(String::stripTrailing)
                            .collect(Collectors.toList());
            return List.of(new LogChunk(pod, targetContainer, from, to, lines));
        } catch (Exception ex) {
            LOGGER.warn("Failed to read logs for pod {} container {} in namespace {}: {}", pod, targetContainer, namespace, ex.getMessage());
            return List.of();
        }
    }

    private Optional<K8sEvent> toEvent(Event event) {
        Instant timestamp = resolveTimestamp(event);
        if (timestamp == null) {
            return Optional.empty();
        }
        String kind = Optional.ofNullable(event.getRegarding()).map(ref -> ref.getKind()).orElse(null);
        String name = Optional.ofNullable(event.getRegarding()).map(ref -> ref.getName()).orElse(null);
        String message = event.getNote();
        if (message == null && event.getSeries() != null) {
            EventSeries series = event.getSeries();
            message = "Series count=" + series.getCount();
        }
        return Optional.of(new K8sEvent(
                timestamp,
                event.getType(),
                event.getReason(),
                message,
                kind,
                name));
    }

    private Instant resolveTimestamp(Event event) {
        return Optional.ofNullable(event.getEventTime())
                .map(this::fromMicroTime)
                .or(() -> parseInstant(event.getDeprecatedLastTimestamp()))
                .or(() -> parseInstant(event.getDeprecatedFirstTimestamp()))
                .orElse(null);
    }

    private Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (Exception ex) {
            LOGGER.debug("Unable to parse timestamp {}: {}", value, ex.getMessage());
            return Optional.empty();
        }
    }

    private Instant fromMicroTime(MicroTime microTime) {
        if (microTime == null || microTime.getTime() == null) {
            return null;
        }
        try {
            return Instant.parse(microTime.getTime());
        } catch (Exception ex) {
            LOGGER.debug("Unable to parse microtime {}: {}", microTime.getTime(), ex.getMessage());
            return null;
        }
    }

    private boolean isWithin(Instant timestamp, Instant from, Instant to) {
        if (timestamp == null) {
            return false;
        }
        boolean afterFrom = from == null || !timestamp.isBefore(from);
        boolean beforeTo = to == null || !timestamp.isAfter(to);
        return afterFrom && beforeTo;
    }

    private Map<String, String> safeMap(Map<String, String> input) {
        return input == null ? Map.of() : Map.copyOf(input);
    }
}
