package com.iadmin.model;

import java.util.List;

public record PodSummary(
        String name,
        String namespace,
        String phase,
        int restarts,
        List<ContainerState> containers
) {
    public static record ContainerState(
            String name,
            boolean ready,
            String lastTerminationReason,
            Integer lastExitCode
    ) {
    }
}
