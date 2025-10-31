package com.iadmin.model;

import java.time.Instant;

public record K8sEvent(
        Instant timestamp,
        String type,
        String reason,
        String message,
        String involvedKind,
        String involvedName
) {
}
