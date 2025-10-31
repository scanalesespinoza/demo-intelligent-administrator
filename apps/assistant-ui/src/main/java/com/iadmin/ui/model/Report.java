package com.iadmin.ui.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    }

    public record Underlying(String kind, String name, List<String> evidence) {
    }

    public record TimelineItem(String t, String type, String text) {
    }
}
