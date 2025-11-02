package com.iadmin.llm;

/**
 * Excepción base para errores al invocar el proveedor de LLM.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
