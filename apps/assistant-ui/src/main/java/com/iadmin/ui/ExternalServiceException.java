package com.iadmin.ui;

import com.iadmin.ui.model.RequestStatus;

public class ExternalServiceException extends RuntimeException {

    private final RequestStatus status;

    public ExternalServiceException(String message, RequestStatus status) {
        super(message);
        this.status = status;
    }

    public ExternalServiceException(String message, RequestStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public RequestStatus status() {
        return status;
    }
}
