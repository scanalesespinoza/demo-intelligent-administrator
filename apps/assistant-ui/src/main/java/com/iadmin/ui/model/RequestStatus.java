package com.iadmin.ui.model;

import java.util.List;

public record RequestStatus(
        String requestId,
        boolean success,
        String message,
        List<String> completedSteps,
        List<String> failedSteps,
        List<String> pendingSteps) {

    public RequestStatus {
        completedSteps = List.copyOf(completedSteps);
        failedSteps = List.copyOf(failedSteps);
        pendingSteps = List.copyOf(pendingSteps);
    }

    public static RequestStatus success(String requestId, List<String> completedSteps) {
        return new RequestStatus(
                requestId,
                true,
                "Proceso completado correctamente.",
                completedSteps,
                List.of(),
                List.of());
    }

    public static RequestStatus failure(
            String requestId,
            String message,
            List<String> completedSteps,
            List<String> failedSteps,
            List<String> pendingSteps) {
        return new RequestStatus(
                requestId,
                false,
                message,
                completedSteps,
                failedSteps,
                pendingSteps);
    }

    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }
}
