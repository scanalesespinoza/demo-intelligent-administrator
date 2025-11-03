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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import java.net.URI;
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
    private static final Pattern RE_URL =
            Pattern.compile("https?://[\\w\\-.]+(?::\\d+)?(?:/[\\w\\-./?%&=]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_START_FAILURE =
            Pattern.compile(
                    "ContainerCannotRun|CreateContainerError|StartError|executable file not found|permission denied",
                    Pattern.CASE_INSENSITIVE);

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

        var healthIndex = buildHealthIndex(deps, pods, events);

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

            var signalsSummary = deriveSignals(deployPods, ev, logs);
            var signals = signalsSummary.signals();
            var cause = inferCause(signalsSummary, logs);
            var confidence = confidenceFor(cause, signalsSummary, logs);

            var timeline = buildTimeline(ev, logs);

            var metrics = buildMetricsSnapshot(deployPods, ev, logs, signalsSummary);
            var dependencies = correlateDependencies(deploy, logs, hints, healthIndex);
            var correlations = buildEvidenceCorrelations(cause, metrics, dependencies, signalsSummary);
            var reasoningHints = buildReasoningHints(cause, metrics, dependencies, signalsSummary);
            var context = new Report.FindingContext(metrics, dependencies, correlations, reasoningHints);

            var dependents = dependencies.values().stream()
                    .map(Report.DependencyHealth::matchedService)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
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
                    c.score(),
                    context);
            findings.add(finding);
        }

        findings.sort(Comparator.comparingInt(Report.Finding::severityScore).reversed());

        var summary = summarize(findings);
        var recs = recommendations(findings);

        var globalHints = buildGlobalHints(findings);

        return new Report(new Report.TimeWindow(from, to), findings, summary, recs, new Report.DiagnosticContext(globalHints));
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

    private SignalsSummary deriveSignals(List<PodSummary> pods,
            List<K8sEvent> ev,
            List<LogChunk> logs) {
        Set<String> signals = new HashSet<>();
        boolean containerOom = false;
        boolean eventOom = false;

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
            boolean containerTerminationOom = Optional.ofNullable(pod.containers()).orElse(List.of()).stream()
                    .map(PodSummary.ContainerState::lastTerminationReason)
                    .filter(Objects::nonNull)
                    .map(reason -> reason.toLowerCase(Locale.ROOT))
                    .anyMatch(reason -> reason.contains("oom"));
            if (containerTerminationOom) {
                signals.add("OOMKilled");
                containerOom = true;
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
            if (RE_START_FAILURE.matcher(combined).find()) {
                signals.add("StartFailure");
            }
            if (combined.toLowerCase(Locale.ROOT).contains("oomkilled")) {
                signals.add("OOMKilled");
                eventOom = true;
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
                if (RE_RANDOM.matcher(line).find()) {
                    signals.add(extractRandomSignal(line));
                }
            }
        }

        var oomEvidence = summarizeOomEvidence(logs);
        if (oomEvidence.hasAnyEvidence()) {
            signals.add("OOMKilled");
        }

        var sortedSignals = signals.stream().filter(s -> s != null && !s.isBlank()).sorted().toList();
        return new SignalsSummary(sortedSignals, oomEvidence, containerOom, eventOom);
    }

    private String extractRandomSignal(String line) {
        var matcher = RE_RANDOM.matcher(line);
        if (matcher.find()) {
            return "RANDOM_FAIL:" + matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return "RANDOM_FAIL";
    }

    private String inferCause(SignalsSummary summary, List<LogChunk> logs) {
        List<String> signals = summary.signals();
        if (containsSignal(signals, "ImagePullBackOff")) {
            return "ImagePull";
        }
        if (containsSignal(signals, "Liveness probe failed")
                || containsSignal(signals, "Readiness probe failed")) {
            return "Probe";
        }
        if (isOomLikely(summary)) {
            return "OOM";
        }
        if (containsSignal(signals, "StartFailure")) {
            return "StartFailure";
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

    private boolean isOomLikely(SignalsSummary summary) {
        if (!containsSignal(summary.signals(), "OOMKilled")) {
            return false;
        }
        OomEvidence evidence = summary.oomEvidence();
        return summary.containerOom()
                || summary.eventOom()
                || (evidence != null && evidence.confirmed());
    }

    private double confidenceFor(String cause, SignalsSummary summary, List<LogChunk> logs) {
        double base = switch (cause) {
            case "ImagePull" -> 0.85;
            case "Probe" -> 0.75;
            case "OOM" -> 0.8;
            case "StartFailure" -> 0.8;
            case "ThreadPoolExhaustion" -> 0.7;
            case "Timeout" -> 0.65;
            case "RandomFail" -> 0.7;
            case "ConfigMissing" -> 0.6;
            default -> 0.35;
        };

        List<String> signals = summary.signals();
        long signalMatches = signals.size();
        long logMatches;
        if ("OOM".equals(cause) && summary.oomEvidence() != null) {
            logMatches = summary.oomEvidence().runtimeMentions() + summary.oomEvidence().killMentions();
        } else {
            logMatches = logs.stream()
                    .flatMap(chunk -> Optional.ofNullable(chunk.lines()).orElse(List.of()).stream())
                    .filter(line -> switch (cause) {
                        case "ImagePull" -> RE_IMAGE.matcher(line).find();
                        case "Probe" -> RE_PROBE.matcher(line).find();
                        case "ThreadPoolExhaustion" -> RE_POOL.matcher(line).find();
                        case "Timeout" -> RE_TIMEOUT.matcher(line).find();
                        case "RandomFail" -> RE_RANDOM.matcher(line).find();
                        case "ConfigMissing" -> line.toLowerCase(Locale.ROOT).contains("configmap")
                                && line.toLowerCase(Locale.ROOT).contains("not found");
                        default -> false;
                    })
                    .count();
        }

        double confidence = base + 0.05 * signalMatches + 0.03 * logMatches;
        if ("OOM".equals(cause)) {
            if (summary.containerOom() || summary.eventOom()) {
                confidence += 0.05;
            } else if (summary.oomEvidence() != null && summary.oomEvidence().confirmed()) {
                confidence += 0.02;
            } else {
                confidence -= 0.2;
            }
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private Map<String, Number> buildMetricsSnapshot(List<PodSummary> pods,
            List<K8sEvent> events,
            List<LogChunk> logs,
            SignalsSummary summary) {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("podCount", pods.size());
        metrics.put("totalRestarts", pods.stream().mapToInt(PodSummary::restarts).sum());
        long notReady = pods.stream()
                .flatMap(p -> Optional.ofNullable(p.containers()).orElse(List.of()).stream())
                .filter(container -> !container.ready())
                .count();
        metrics.put("notReadyContainers", notReady);

        long warningEvents = events.stream()
                .filter(event -> "warning".equalsIgnoreCase(orEmpty(event.type())))
                .count();
        metrics.put("warningEvents", warningEvents);

        long probeFailures = events.stream()
                .filter(event -> RE_PROBE.matcher((orEmpty(event.reason()) + " " + orEmpty(event.message()))).find())
                .count();
        metrics.put("probeFailureEvents", probeFailures);

        long administrativeDeletion = events.stream()
                .filter(event -> containsIgnoreCase(event.reason(), "Killing")
                        && containsIgnoreCase(event.message(), "Deletion"))
                .count();
        metrics.put("administrativeDeletionEvents", administrativeDeletion);

        long errorLines = logs.stream()
                .flatMap(chunk -> Optional.ofNullable(chunk.lines()).orElse(List.of()).stream())
                .filter(this::isLikelyErrorLine)
                .count();
        metrics.put("logErrorLines", errorLines);

        metrics.put("containerTerminationOom", summary.containerOom() ? 1 : 0);
        metrics.put("eventOomSignals", summary.eventOom() ? 1 : 0);
        if (summary.oomEvidence() != null) {
            metrics.put("oomLogMentions", summary.oomEvidence().runtimeMentions());
            metrics.put("oomStackFrames", summary.oomEvidence().stackFrames());
            metrics.put("kernelOomEvents", summary.oomEvidence().killMentions());
            metrics.put("confirmedOomEvidence", summary.oomEvidence().confirmed() ? 1 : 0);
        }

        return metrics;
    }

    private Map<String, Report.DependencyHealth> correlateDependencies(
            String deployment,
            List<LogChunk> logs,
            Map<String, String> configHints,
            Map<String, ServiceHealthSnapshot> healthIndex) {
        Map<String, Report.DependencyHealth> dependencies = new LinkedHashMap<>();
        Map<String, Integer> urlMentions = new LinkedHashMap<>();

        for (var chunk : logs) {
            for (var line : Optional.ofNullable(chunk.lines()).orElse(List.of())) {
                if (!isLikelyErrorLine(line)) {
                    continue;
                }
                var matcher = RE_URL.matcher(line);
                while (matcher.find()) {
                    String url = matcher.group();
                    urlMentions.merge(url, 1, Integer::sum);
                }
            }
        }

        configHints.values().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith("http"))
                .forEach(value -> urlMentions.putIfAbsent(value, 0));

        for (var entry : urlMentions.entrySet()) {
            String url = entry.getKey();
            String host = extractHost(url);
            if (host == null || host.isBlank()) {
                continue;
            }
            String matched = matchDeployment(host, healthIndex.keySet());
            if (matched != null && matched.equalsIgnoreCase(deployment)) {
                continue;
            }
            ServiceHealthSnapshot snapshot = matched != null ? healthIndex.get(matched) : null;
            var signals = snapshot != null ? snapshot.signals() : List.<String>of();
            dependencies.put(url, new Report.DependencyHealth(
                    url,
                    matched,
                    snapshot != null ? snapshot.status() : "unknown",
                    snapshot != null ? snapshot.totalRestarts() : 0,
                    snapshot != null ? snapshot.notReadyContainers() : 0,
                    snapshot != null ? snapshot.warningEvents() : 0,
                    signals));
        }

        return dependencies;
    }

    private List<String> buildEvidenceCorrelations(String cause,
            Map<String, Number> metrics,
            Map<String, Report.DependencyHealth> dependencies,
            SignalsSummary summary) {
        List<String> correlations = new ArrayList<>();

        Number restarts = metrics.getOrDefault("totalRestarts", 0);
        Number warnings = metrics.getOrDefault("warningEvents", 0);
        if (restarts.longValue() > 0 && warnings.longValue() == 0) {
            correlations.add("Hay reinicios registrados sin eventos de Warning recientes; podría tratarse de un redeploy controlado.");
        }
        if (metrics.getOrDefault("administrativeDeletionEvents", 0).longValue() > 0) {
            correlations.add("Kubernetes reportó eventos de Killing por eliminación; validar si corresponde a una intervención manual.");
        }
        if ("OOM".equals(cause) && summary.oomEvidence() != null && !summary.oomEvidence().confirmed()) {
            correlations.add("Se mencionó OOM sin stacktrace ni señal del kernel; considerar falso positivo antes de escalar.");
        }

        for (var dep : dependencies.values()) {
            if (dep.matchedService() != null) {
                String statusText = dep.status();
                if (dep.signals() != null && !dep.signals().isEmpty()) {
                    statusText += " con señales " + String.join(", ", dep.signals());
                }
                correlations.add(String.format(Locale.ROOT,
                        "Los logs fallaron contra %s y el servicio %s muestra estado %s (restarts=%d, warnings=%d)",
                        dep.url(),
                        dep.matchedService(),
                        statusText,
                        dep.restartCount(),
                        dep.recentEvents()));
            } else {
                correlations.add(String.format(Locale.ROOT,
                        "Los logs apuntan a %s pero no se identificó un despliegue asociado en el clúster.",
                        dep.url()));
            }
        }

        return correlations;
    }

    private List<String> buildReasoningHints(String cause,
            Map<String, Number> metrics,
            Map<String, Report.DependencyHealth> dependencies,
            SignalsSummary summary) {
        List<String> hints = new ArrayList<>();
        hints.add("Cruza metrics.totalRestarts con metrics.warningEvents para dimensionar la severidad real.");
        if (!dependencies.isEmpty()) {
            hints.add("Valida si los servicios en context.dependencies también presentan hallazgos en el reporte.");
        }
        if (containsSignal(summary.signals(), "OOMKilled") || "OOM".equals(cause)) {
            hints.add("Confirma un OOM solo si metrics.confirmedOomEvidence == 1 o hay OOMKilled en eventos/terminaciones.");
        }
        if (metrics.getOrDefault("administrativeDeletionEvents", 0).intValue() > 0) {
            hints.add("Considera que hubo eliminaciones manuales de pods; podrían explicar reinicios sin incidentes reales.");
        }
        hints.add("Explica explícitamente cuando falte evidencia que respalde una hipótesis.");
        return hints;
    }

    private Map<String, ServiceHealthSnapshot> buildHealthIndex(List<DeploymentSummary> deps,
            List<PodSummary> pods,
            List<K8sEvent> events) {
        Map<String, ServiceHealthSnapshot> index = new HashMap<>();
        for (var deployment : deps) {
            var deployPods = pods.stream()
                    .filter(p -> belongsToDeployment(p, deployment.name()))
                    .toList();
            var deployEvents = filterEventsFor(events, deployment.name());
            var signalsSummary = deriveSignals(deployPods, deployEvents, List.of());
            String status = statusFor(signalsSummary.signals());
            int totalRestarts = deployPods.stream().mapToInt(PodSummary::restarts).sum();
            int notReady = (int) deployPods.stream()
                    .flatMap(p -> Optional.ofNullable(p.containers()).orElse(List.of()).stream())
                    .filter(container -> !container.ready())
                    .count();
            int warningEvents = (int) deployEvents.stream()
                    .filter(event -> "warning".equalsIgnoreCase(orEmpty(event.type())))
                    .count();
            index.put(deployment.name(), new ServiceHealthSnapshot(
                    deployment.name(),
                    status,
                    totalRestarts,
                    notReady,
                    deployPods.size(),
                    warningEvents,
                    signalsSummary.signals()));
        }
        return index;
    }

    private List<String> buildGlobalHints(List<Report.Finding> findings) {
        List<String> hints = new ArrayList<>();
        hints.add("Correlaciona señales, métricas y eventos antes de concluir una causa.");
        boolean hasDependencies = findings.stream()
                .map(Report.Finding::context)
                .filter(Objects::nonNull)
                .anyMatch(ctx -> ctx.dependencies() != null && !ctx.dependencies().isEmpty());
        if (hasDependencies) {
            hints.add("Describe cómo los hallazgos en dependencies influyen en el servicio analizado.");
        }
        boolean confirmedOom = findings.stream()
                .map(Report.Finding::context)
                .filter(Objects::nonNull)
                .map(ctx -> ctx.metrics().getOrDefault("confirmedOomEvidence", 0))
                .mapToInt(Number::intValue)
                .sum() > 0;
        if (confirmedOom) {
            hints.add("Cuando haya OOM, especifica si la evidencia proviene de stacktrace, eventos o terminaciones.");
        }
        hints.add("Si la información es insuficiente para una causa, indícalo claramente.");
        return hints;
    }

    private OomEvidence summarizeOomEvidence(List<LogChunk> logs) {
        int runtimeMentions = 0;
        int stackFrames = 0;
        int killMentions = 0;
        boolean awaitingStack = false;
        for (var chunk : logs) {
            for (var line : Optional.ofNullable(chunk.lines()).orElse(List.of())) {
                if (isRuntimeOomLine(line)) {
                    runtimeMentions++;
                    awaitingStack = true;
                    continue;
                }
                if (awaitingStack) {
                    if (isStackFrameLine(line)) {
                        stackFrames++;
                    } else if (!line.isBlank()) {
                        awaitingStack = false;
                    }
                }
                if (isKernelOomLine(line)) {
                    killMentions++;
                }
            }
            awaitingStack = false;
        }
        boolean confirmed = runtimeMentions > 0 && (stackFrames > 0 || killMentions > 0);
        return new OomEvidence(confirmed, runtimeMentions, stackFrames, killMentions);
    }

    private boolean isRuntimeOomLine(String line) {
        if (line == null) {
            return false;
        }
        if (!RE_OOM.matcher(line).find()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("-xx:") || lower.contains("exitonoutofmemoryerror") || lower.contains("useparallelgc")) {
            return false;
        }
        return lower.contains("exception")
                || lower.contains("error")
                || lower.contains("outofmemoryerror:")
                || lower.contains("java.lang.outofmemoryerror")
                || lower.contains("killed process");
    }

    private boolean isStackFrameLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("at ") && trimmed.contains("(");
    }

    private boolean isKernelOomLine(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("killed process")
                || lower.contains("oom-kill")
                || lower.contains("memory cgroup")
                || lower.contains("invoked oom-killer");
    }

    private boolean isLikelyErrorLine(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("exception")
                || lower.contains("fail")
                || lower.contains("timeout")
                || lower.contains("refused")
                || lower.contains("unavailable")
                || lower.contains("5xx");
    }

    private String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host;
            }
            String authority = uri.getAuthority();
            if (authority != null && !authority.isBlank()) {
                return authority;
            }
            String value = url;
            int schemeIdx = value.indexOf("//");
            if (schemeIdx >= 0) {
                value = value.substring(schemeIdx + 2);
            }
            int slash = value.indexOf('/');
            if (slash > 0) {
                value = value.substring(0, slash);
            }
            int question = value.indexOf('?');
            if (question > 0) {
                value = value.substring(0, question);
            }
            return value;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String matchDeployment(String host, Set<String> deployments) {
        if (host == null) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            normalized = normalized.substring(0, colon);
        }
        String simple = normalized.contains(".") ? normalized.substring(0, normalized.indexOf('.')) : normalized;
        for (String candidate : deployments) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.equals(normalized) || lower.equals(simple)) {
                return candidate;
            }
        }
        return null;
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
                || containsSignal(signalSet, "Readiness probe failed")
                || containsSignal(signalSet, "StartFailure")) {
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
                "StartFailure", "Revisar el comando/entrypoint del contenedor y que los binarios referenciados existan.",
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

    private record SignalsSummary(
            List<String> signals,
            OomEvidence oomEvidence,
            boolean containerOom,
            boolean eventOom) {
    }

    private record OomEvidence(boolean confirmed, int runtimeMentions, int stackFrames, int killMentions) {
        boolean hasAnyEvidence() {
            return runtimeMentions > 0 || killMentions > 0;
        }
    }

    private record ServiceHealthSnapshot(
            String deployment,
            String status,
            int totalRestarts,
            int notReadyContainers,
            int podCount,
            int warningEvents,
            List<String> signals) {
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
