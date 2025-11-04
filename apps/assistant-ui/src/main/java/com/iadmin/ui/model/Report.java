package com.iadmin.ui.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record Report(
        TimeWindow window,
        List<Finding> findings,
        String summary,
        List<String> recommendations) {

    public record TimeWindow(Instant from, Instant to) {
    }

    public record Finding(
            String service,
            String namespace,
            String status,
            List<String> signals,
            List<String> dependents,
            List<Underlying> underlying,
            Map<String, String> configHints,
            List<TimelineItem> timeline,
            String causeLikely,
            double confidence,
            int severityScore) {

        public List<String> describedSignals() {
            if (signals == null || signals.isEmpty()) {
                return List.of();
            }
            return signals.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Finding::describeSignal)
                    .distinct()
                    .collect(Collectors.toList());
        }

        private static String describeSignal(String signal) {
            String trimmed = signal.trim();
            return switch (trimmed) {
                case "NOT_FOUND" -> "Sin datos operativos disponibles";
                case "Liveness probe failed" -> "Falla en liveness probe";
                case "Readiness probe failed" -> "Falla en readiness probe";
                case "CrashLoopBackOff" -> "CrashLoopBackOff";
                case "ImagePullBackOff" -> "Falla al obtener la imagen";
                case "HighRestarts" -> "Reinicios elevados";
                case "ContainerNotReady" -> "Contenedores no listos";
                case "OOMKilled" -> "Terminaciones por OOM";
                case "RejectedExecution" -> "Pool de hilos saturado";
                case "timeout" -> "Timeouts en dependencias";
                case "ConfigMissing" -> "ConfigMap/Secret no encontrado";
                case "StartFailure" -> "Fallo al iniciar el contenedor";
                default -> trimmed.startsWith("RANDOM_FAIL")
                        ? formatRandom(trimmed)
                        : trimmed;
            };
        }

        private static String formatRandom(String signal) {
            String suffix = signal.substring("RANDOM_FAIL".length());
            if (suffix.startsWith(":")) {
                suffix = suffix.substring(1);
            }
            if (suffix.isBlank()) {
                return "Fallo aleatorio inyectado";
            }
            return "Fallo aleatorio inyectado (" + suffix.toLowerCase(Locale.ROOT) + ")";
        }
    }

    public record Underlying(String kind, String name, List<String> evidence) {
    }

    public record TimelineItem(String t, String type, String text) {
    }
}
