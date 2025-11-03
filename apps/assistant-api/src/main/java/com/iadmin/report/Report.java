package com.iadmin.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Report(
        TimeWindow window,
        List<Finding> findings,
        String summary,
        List<String> recommendations,
        DiagnosticContext context
) {
    public record TimeWindow(Instant from, Instant to) {
    }

    public static record Finding(
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
            int severityScore,
            FindingContext context
    ) {
    }

    public static record Underlying(String kind, String name, List<String> evidence) {
    }

    public static record TimelineItem(String t, String type, String text) {
    }

    public static record FindingContext(
            Map<String, Number> metrics,
            Map<String, DependencyHealth> dependencies,
            List<String> evidenceCorrelations,
            List<String> reasoningHints
    ) {
    }

    public static record DependencyHealth(
            String url,
            String matchedService,
            String status,
            int restartCount,
            int notReadyContainers,
            int recentEvents,
            List<String> signals
    ) {
    }

    public static record DiagnosticContext(List<String> globalHints) {
    }
}
