package com.iadmin.model;

import java.time.Instant;
import java.util.List;

public record LogChunk(
        String pod,
        String container,
        Instant from,
        Instant to,
        List<String> lines
) {
}
