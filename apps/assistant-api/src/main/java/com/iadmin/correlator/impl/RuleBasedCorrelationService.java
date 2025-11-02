package com.iadmin.correlator.impl;

import com.iadmin.config.ConfigConnector;
import com.iadmin.correlator.CorrelationService;
import com.iadmin.k8s.K8sConnector;
import com.iadmin.model.ConfigSnapshot;
import com.iadmin.model.DeploymentSummary;
import com.iadmin.model.K8sEvent;
import com.iadmin.model.LogChunk;
import com.iadmin.model.PodSummary;
import com.iadmin.report.Report;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RuleBasedCorrelationService implements CorrelationService {

    private static final Logger LOGGER = Logger.getLogger("API.CorrelationService");

    @Inject
    K8sConnector k8s;

    @Inject
    ConfigConnector cfg;

    private static final Pattern RE_POOL =
            Pattern.compile("RejectedExecution|no idle threads|thread pool exhausted|TooManyRequests", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_TIMEOUT =
            Pattern.compile("timeout|timed out|deadline exceeded|connection timed out|socket timeout", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_OOM =
            Pattern.compile("OOMKilled|OutOfMemoryError", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IMAGE =
            Pattern.compile("ImagePullBackOff|ErrImagePull", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_PROBE =
            Pattern.compile("Liveness probe failed|Readiness probe failed", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_RANDOM =
            Pattern.compile("RANDOM_FAIL:(exit|loop|oom|io|probe)", Pattern.CASE_INSENSITIVE);

    @PostConstruct
    void init() {
        LOGGER.infov(
                "[INIT] RuleBasedCorrelationService listo. k8s={0}, cfg={1}",
                k8s != null ? k8s.getClass().getSimpleName() : "<null>",
                cfg != null ? cfg.getClass().getSimpleName() : "<null>");
    }

    @Override
    public Report analyze(String ns, Instant from, Instant to, int topN) {
        String requestId = UUID.randomUUID().toString();
        var deps = callWithLogging(
                requestId,
                "Kubernetes",
                "listDeployments",
                () -> k8s.listDeployments(ns),
                "namespace=" + ns);
        var pods = callWithLogging(
                requestId,
                "Kubernetes",
                "listPods",
                () -> k8s.listPods(ns, Map.of()),
                "namespace=" + ns);
        var events = callWithLogging(
                requestId,
                "Kubernetes",
                "getEvents",
                () -> k8s.getEvents(ns, from, to),
                String.format("namespace=%s from=%s to=%s", ns, from, to));

        record Candidate(String deploy, int score) {
        }

        List<Candidate> ranked = deps.stream()
                .map(d -> new Candidate(d.name(), severityFor(d, pods, events)))
                .sorted(Comparator.comparingInt(Candidate::score).reversed())
                .limit(Math.max(topN, 5))
                .toList();

        List<Report.Finding> findings = new ArrayList<>();
        for (var c : ranked) {
            var deploy = c.deploy();
            var deployPods = pods.stream().filter(p -> belongsToDeployment(p, deploy)).toList();
            var ev = filterEventsFor(events, deploy);
            var logs = collectLogs(requestId, ns, deploy, deployPods, from, to, 300);

            var config = callWithLogging(
                    requestId,
                    "Kubernetes",
                    "getDeploymentConfig",
                    () -> cfg.getDeploymentConfig(ns, deploy),
                    String.format("namespace=%s deployment=%s", ns, deploy));
            var hints = extractConfigHints(config);

            var signals = deriveSignals(deployPods, ev, logs);
            var cause = inferCause(signals, logs);
            var confidence = confidenceFor(cause, signals, logs);

            var timeline = buildTimeline(ev, logs);

            var dependents = List.<String>of();
            var underlying = List.<Report.Underlying>of();

            var status = statusFor(signals);
            var finding = new Report.Finding(
                    deploy,
                    ns,
                    status,
                    signals,
                    dependents,
                    underlying,
                    hints,
                    timeline,
                    cause,
                    confidence,
                    c.score());
            findings.add(finding);
        }

        findings.sort(Comparator.comparingInt(Report.Finding::severityScore).reversed());

        var summary = summarize(findings);
        var recs = recommendations(findings);

        return new Report(new Report.TimeWindow(from, to), findings, summary, recs);
    }

    private int severityFor(DeploymentSummary d,
            List<PodSummary> pods,
            List<K8sEvent> events) {
        int score = 0;
        var relatedPods = pods.stream()
                .filter(p -> belongsToDeployment(p, d.name()))
                .toList();
        if (d.available() != null && d.desired() != null && d.available() < d.desired()) {
            score += 1;
        }

        boolean crashLoop = events.stream()
                .filter(e -> belongsToDeploymentEvent(e, d.name()))
                .anyMatch(e -> containsIgnoreCase(e.reason(), "CrashLoopBackOff")
                        || containsIgnoreCase(e.message(), "CrashLoopBackOff"));
        if (!crashLoop) {
            crashLoop = relatedPods.stream()
                    .map(PodSummary::phase)
                    .filter(v -> v != null)
                    .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("crashloop"));
        }
        if (crashLoop) {
            score += 3;
        }

        boolean imagePull = events.stream()
                .filter(e -> belongsToDeploymentEvent(e, d.name()))
                .anyMatch(e -> RE_IMAGE.matcher(orEmpty(e.reason()) + " " + orEmpty(e.message())).find());
        if (imagePull) {
            score += 3;
        }

        boolean probeFail = events.stream()
                .filter(e -> belongsToDeploymentEvent(e, d.name()))
                .anyMatch(e -> RE_PROBE.matcher(orEmpty(e.reason()) + " " + orEmpty(e.message())).find());
        if (probeFail) {
            score += 2;
        }

        boolean manyRestarts = relatedPods.stream().anyMatch(p -> p.restarts() > 3);
        if (manyRestarts) {
            score += 2;
        }

        return score;
    }

    private boolean belongsToDeployment(PodSummary p, String deploy) {
        if (p == null || deploy == null || p.name() == null) {
            return false;
        }
        return p.name().startsWith(deploy + "-");
    }

    private boolean belongsToDeploymentEvent(K8sEvent event, String deploy) {
        if (event == null || event.involvedName() == null || deploy == null) {
            return false;
        }
        return event.involvedName().equals(deploy)
                || event.involvedName().startsWith(deploy + "-");
    }

    private List<K8sEvent> filterEventsFor(List<K8sEvent> ev, String deploy) {
        return ev.stream()
                .filter(e -> belongsToDeploymentEvent(e, deploy))
                .toList();
    }

    private List<LogChunk> collectLogs(String requestId, String ns, String deploy, List<PodSummary> pods,
            Instant from, Instant to, int tail) {
        List<LogChunk> chunks = new ArrayList<>();
        for (var pod : pods) {
            List<PodSummary.ContainerState> containers = Optional.ofNullable(pod.containers()).orElse(List.of());
            for (var container : containers) {
                Instant start = Instant.now();
                LOGGER.infov(
                        "[COMM-START] requestId={0} target=Kubernetes action=getPodLogs namespace={1} deployment={2} pod={3} container={4}",
                        requestId,
                        ns,
                        deploy,
                        pod.name(),
                        container.name());
                List<LogChunk> podLogs = Optional.ofNullable(
                                k8s.getPodLogs(ns, pod.name(), container.name(), from, to, tail))
                        .orElse(List.of());
                LOGGER.infov(
                        "[COMM-END] requestId={0} target=Kubernetes action=getPodLogs namespace={1} deployment={2} pod={3} container={4} durationMs={5}",
                        requestId,
                        ns,
                        deploy,
                        pod.name(),
                        container.name(),
                        Duration.between(start, Instant.now()).toMillis());
                chunks.addAll(podLogs);
            }
        }
        return chunks;
    }

    private <T> T callWithLogging(String requestId, String target, String action, Supplier<T> supplier, String context) {
        Instant start = Instant.now();
        LOGGER.infov(
                "[COMM-START] requestId={0} target={1} action={2} context={3}",
                requestId,
                target,
                action,
                context);
        try {
            T result = supplier.get();
            LOGGER.infov(
                    "[COMM-END] requestId={0} target={1} action={2} durationMs={3}",
                    requestId,
                    target,
                    action,
                    Duration.between(start, Instant.now()).toMillis());
            return result;
        } catch (RuntimeException e) {
            LOGGER.errorf(
                    e,
                    "[COMM-ERROR] requestId=%s target=%s action=%s context=%s",
                    requestId,
                    target,
                    action,
                    context);
            throw e;
        }
    }

    private Map<String, String> extractConfigHints(ConfigSnapshot snap) {
        var m = new HashMap<String, String>();
        if (snap == null || snap.env() == null) {
            return m;
        }
        snap.env().forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }
            String upper = k.toUpperCase(Locale.ROOT);
            if (upper.contains("URL") || upper.contains("HOST")) {
                m.put(k, v);
            }
            if (upper.contains("LOG") || upper.contains("POOL") || upper.contains("THREAD")) {
                m.put(k, v);
            }
        });
        return m;
    }

    private List<String> deriveSignals(List<PodSummary> pods,
            List<K8sEvent> ev,
            List<LogChunk> logs) {
        Set<String> signals = new HashSet<>();

        for (var pod : pods) {
            if (pod.phase() != null) {
                String phaseLower = pod.phase().toLowerCase(Locale.ROOT);
                if (phaseLower.contains("crashloop")) {
                    signals.add("CrashLoopBackOff");
                }
                if (phaseLower.contains("imagepull")) {
                    signals.add("ImagePullBackOff");
                }
            }
            if (pod.restarts() > 3) {
                signals.add("HighRestarts");
            }
            boolean anyNotReady = Optional.ofNullable(pod.containers()).orElse(List.of()).stream()
                    .anyMatch(c -> !c.ready());
            if (anyNotReady) {
                signals.add("ContainerNotReady");
            }
        }

        for (var event : ev) {
            String reason = orEmpty(event.reason());
            String message = orEmpty(event.message());
            String combined = reason + " " + message;
            if (reason.contains("CrashLoopBackOff") || message.contains("CrashLoopBackOff")) {
                signals.add("CrashLoopBackOff");
            }
            if (RE_IMAGE.matcher(combined).find()) {
                signals.add("ImagePullBackOff");
            }
            if (RE_PROBE.matcher(combined).find()) {
                if (combined.toLowerCase(Locale.ROOT).contains("readiness")) {
                    signals.add("Readiness probe failed");
                } else {
                    signals.add("Liveness probe failed");
                }
            }
            if (combined.toLowerCase(Locale.ROOT).contains("oomkilled")) {
                signals.add("OOMKilled");
            }
            if (combined.toLowerCase(Locale.ROOT).contains("configmap")
                    && combined.toLowerCase(Locale.ROOT).contains("not found")) {
                signals.add("ConfigMissing");
            }
        }

        for (var chunk : logs) {
            for (var line : Optional.ofNullable(chunk.lines()).orElse(List.of())) {
                if (RE_POOL.matcher(line).find()) {
                    signals.add("RejectedExecution");
                }
                if (RE_TIMEOUT.matcher(line).find()) {
                    signals.add("timeout");
                }
                if (RE_OOM.matcher(line).find()) {
                    signals.add("OOMKilled");
                }
                if (RE_RANDOM.matcher(line).find()) {
                    signals.add(extractRandomSignal(line));
                }
            }
        }

        return signals.stream().filter(s -> s != null && !s.isBlank()).sorted().toList();
    }

    private String extractRandomSignal(String line) {
        var matcher = RE_RANDOM.matcher(line);
        if (matcher.find()) {
            return "RANDOM_FAIL:" + matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return "RANDOM_FAIL";
    }

    private String inferCause(List<String> signals, List<LogChunk> logs) {
        if (containsSignal(signals, "ImagePullBackOff")) {
            return "ImagePull";
        }
        if (containsSignal(signals, "Liveness probe failed")
                || containsSignal(signals, "Readiness probe failed")) {
            return "Probe";
        }
        if (containsSignal(signals, "OOMKilled") || logsMatch(logs, RE_OOM)) {
            return "OOM";
        }
        if (containsSignal(signals, "RejectedExecution") || logsMatch(logs, RE_POOL)) {
            return "ThreadPoolExhaustion";
        }
        if (containsSignal(signals, "timeout") || logsMatch(logs, RE_TIMEOUT)) {
            return "Timeout";
        }
        if (signals.stream().anyMatch(s -> s.startsWith("RANDOM_FAIL"))) {
            return "RandomFail";
        }
        if (containsSignal(signals, "ConfigMissing")) {
            return "ConfigMissing";
        }
        return "Unknown";
    }

    private boolean logsMatch(List<LogChunk> logs, Pattern pattern) {
        return logs.stream()
                .flatMap(chunk -> Optional.ofNullable(chunk.lines()).orElse(List.of()).stream())
                .anyMatch(line -> pattern.matcher(line).find());
    }

    private double confidenceFor(String cause, List<String> signals, List<LogChunk> logs) {
        double base = switch (cause) {
            case "ImagePull" -> 0.85;
            case "Probe" -> 0.75;
            case "OOM" -> 0.8;
            case "ThreadPoolExhaustion" -> 0.7;
            case "Timeout" -> 0.65;
            case "RandomFail" -> 0.7;
            case "ConfigMissing" -> 0.6;
            default -> 0.35;
        };

        long signalMatches = signals.size();
        long logMatches = logs.stream()
                .flatMap(chunk -> Optional.ofNullable(chunk.lines()).orElse(List.of()).stream())
                .filter(line -> switch (cause) {
                    case "ImagePull" -> RE_IMAGE.matcher(line).find();
                    case "Probe" -> RE_PROBE.matcher(line).find();
                    case "OOM" -> RE_OOM.matcher(line).find();
                    case "ThreadPoolExhaustion" -> RE_POOL.matcher(line).find();
                    case "Timeout" -> RE_TIMEOUT.matcher(line).find();
                    case "RandomFail" -> RE_RANDOM.matcher(line).find();
                    case "ConfigMissing" -> line.toLowerCase(Locale.ROOT).contains("configmap")
                            && line.toLowerCase(Locale.ROOT).contains("not found");
                    default -> false;
                })
                .count();

        double confidence = base + 0.05 * signalMatches + 0.03 * logMatches;
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private List<Report.TimelineItem> buildTimeline(List<K8sEvent> ev,
            List<LogChunk> logs) {
        List<Report.TimelineItem> items = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        for (var event : ev) {
            Instant ts = Optional.ofNullable(event.timestamp()).orElse(Instant.EPOCH);
            String text = Stream.of(event.reason(), event.message())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(" - "));
            if (text.isBlank()) {
                text = "Evento sin descripción";
            }
            items.add(new Report.TimelineItem(formatter.format(ts), "event", text));
        }

        for (var chunk : logs) {
            Instant ts = Optional.ofNullable(chunk.from()).orElse(Optional.ofNullable(chunk.to()).orElse(Instant.EPOCH));
            String joined = Optional.ofNullable(chunk.lines()).orElse(List.of()).stream()
                    .limit(5)
                    .collect(Collectors.joining("\n"));
            if (joined.isBlank()) {
                joined = "(sin logs)";
            }
            String text = chunk.pod() + "/" + chunk.container() + "\n" + joined;
            items.add(new Report.TimelineItem(formatter.format(ts), "log", text));
        }

        items.sort(Comparator.comparing(Report.TimelineItem::t));
        return items;
    }

    private String statusFor(List<String> signals) {
        Set<String> signalSet = new HashSet<>(signals);
        if (containsSignal(signalSet, "CrashLoopBackOff")
                || containsSignal(signalSet, "ImagePullBackOff")
                || containsSignal(signalSet, "Liveness probe failed")
                || containsSignal(signalSet, "Readiness probe failed")) {
            return "Stopped";
        }
        if (containsSignal(signalSet, "OOMKilled")
                || containsSignal(signalSet, "RejectedExecution")
                || containsSignal(signalSet, "timeout")
                || containsSignal(signalSet, "HighRestarts")) {
            return "Degraded";
        }
        if (signals.isEmpty()) {
            return "Healthy";
        }
        return "Unknown";
    }

    private String summarize(List<Report.Finding> findings) {
        if (findings.isEmpty()) {
            return "No se detectaron servicios con problemas en la ventana analizada.";
        }
        long problematic = findings.stream()
                .filter(f -> !"Healthy".equalsIgnoreCase(f.status()))
                .count();

        Map<String, Long> causeCounts = findings.stream()
                .map(Report.Finding::causeLikely)
                .filter(cause -> cause != null && !cause.equals("Unknown"))
                .collect(Collectors.groupingBy(cause -> cause, TreeMap::new, Collectors.counting()));

        String causeSummary = causeCounts.isEmpty()
                ? "causas no determinadas"
                : causeCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .map(e -> e.getKey() + " (" + e.getValue() + ")")
                        .collect(Collectors.joining(", "));

        return String.format(Locale.ROOT,
                "%d servicios con señales de incidencia; principales causas: %s.",
                problematic,
                causeSummary);
    }

    private List<String> recommendations(List<Report.Finding> findings) {
        Map<String, String> advice = Map.of(
                "ThreadPoolExhaustion", "Revisar la configuración del pool de hilos y ajustar límites de concurrencia.",
                "ImagePull", "Verificar credenciales y disponibilidad de la imagen en el registro.",
                "OOM", "Ajustar límites/memoria de los pods o revisar fugas en la aplicación.",
                "Probe", "Revisar umbrales de probes y tiempos de arranque del servicio.",
                "Timeout", "Verificar latencia/errores en dependencias upstream y tiempos de espera.",
                "RandomFail", "Analizar el componente random-failer y revisar los artefactos de falla.",
                "ConfigMissing", "Confirmar que ConfigMaps/Secrets requeridos estén presentes y referenciados.");

        return findings.stream()
                .map(Report.Finding::causeLikely)
                .filter(cause -> cause != null && !cause.equals("Unknown"))
                .distinct()
                .map(cause -> advice.getOrDefault(cause, "Investigar manualmente la causa reportada."))
                .toList();
    }

    private boolean containsSignal(Set<String> signals, String expected) {
        return signals.stream().anyMatch(s -> s.equalsIgnoreCase(expected));
    }

    private boolean containsSignal(List<String> signals, String expected) {
        return signals.stream().anyMatch(s -> s.equalsIgnoreCase(expected));
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
