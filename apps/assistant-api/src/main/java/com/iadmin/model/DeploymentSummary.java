package com.iadmin.model;

import java.util.Map;

public record DeploymentSummary(
        String name,
        String namespace,
        Integer desired,
        Integer available,
        Map<String, String> labels,
        Map<String, String> annotations
) {
}
