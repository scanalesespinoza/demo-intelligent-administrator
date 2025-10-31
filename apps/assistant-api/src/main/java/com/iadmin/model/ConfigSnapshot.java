package com.iadmin.model;

import java.util.List;
import java.util.Map;

public record ConfigSnapshot(
        Map<String, String> env,
        List<String> configMapRefs,
        Map<String, String> configMapData
) {
}
