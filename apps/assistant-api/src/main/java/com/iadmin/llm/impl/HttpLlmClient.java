package com.iadmin.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iadmin.llm.LlmClient;
import com.iadmin.llm.LlmException;
import com.iadmin.report.Report;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HttpLlmClient implements LlmClient {

    private static final Logger LOGGER = Logger.getLogger("API.HttpLlmClient");

    static final String SYSTEM_PROMPT = """
            Eres iAdmin. Recibirás un JSON `report` con hallazgos técnicos.
            - NO inventes datos. Solo explica lo que contiene `report`.
            - Escribe en español, claro y conciso.
            - Secciones: Resumen, Servicios afectados (con síntomas), Evidencia clave (eventos/logs), Posibles causas, Recomendaciones.
            - Si algo falta, dilo explícitamente (“no se encontró evidencia de …”).
            """;

    private static final String DEFAULT_MODEL = "granite-7b-instruct";

    @ConfigProperty(name = "llm.endpoint")
    String endpoint;

    @ConfigProperty(name = "llm.model")
    Optional<String> model = Optional.empty();

    @ConfigProperty(name = "llm.api-key")
    Optional<String> apiKey = Optional.empty();

    @Inject
    ObjectMapper mapper;

    private volatile HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    void init() {
        LOGGER.infov(
                "[INIT] HttpLlmClient listo. endpoint={0} model={1} apiKeyConfigurada={2}",
                endpoint,
                resolveModel(),
                apiKey.map(value -> !value.isBlank()).orElse(false));
    }

    @Override
    public String redactReport(Report report) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new LlmException("llm.endpoint no configurado");
        }

        URI target;
        try {
            target = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new LlmException("llm.endpoint inválido: " + endpoint, e);
        }

        String requestId = UUID.randomUUID().toString();
        Instant start = Instant.now();
        try {
            var prettyReport = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            var user = Map.of(
                    "role", "user",
                    "content", "report:\n```json\n" + prettyReport + "\n```"
            );
            var sys = Map.of(
                    "role", "system",
                    "content", SYSTEM_PROMPT
            );
            var body = Map.of(
                    "model", resolveModel(),
                    "messages", List.of(sys, user),
                    "temperature", 0.2,
                    "max_tokens", 800
            );

            var builder = HttpRequest.newBuilder(target)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

            apiKey.map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .ifPresent(value -> builder.header("Authorization", "Bearer " + value));

            var request = builder.build();

            LOGGER.infov(
                    "[COMM-START] requestId={0} target={1} model={2}",
                    requestId,
                    target,
                    resolveModel());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            LOGGER.infov(
                    "[COMM-END] requestId={0} target={1} status={2} durationMs={3}",
                    requestId,
                    target,
                    response.statusCode(),
                    durationMs);
            if (response.statusCode() >= 400) {
                throw new LlmException("LLM call failed with status " + response.statusCode());
            }
            return extractContent(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.errorf(e, "[COMM-ERROR] requestId=%s target=%s llamada interrumpida", requestId, target);
            throw new LlmException("LLM call interrupted", e);
        } catch (IOException e) {
            if (isCausedBy(e, java.net.ConnectException.class)) {
                String message = "No se pudo conectar con el endpoint del LLM en '" + target
                        + "'. Verifique la URL y que el servicio esté accesible.";
                LOGGER.errorf(e, "[COMM-ERROR] requestId=%s target=%s model=%s", requestId, target, resolveModel());
                throw new LlmException(message, e);
            }
            LOGGER.errorf(e, "[COMM-ERROR] requestId=%s target=%s model=%s", requestId, target, resolveModel());
            throw new LlmException("Error de E/S al invocar el LLM", e);
        } catch (Exception e) {
            LOGGER.errorf(e, "[COMM-ERROR] requestId=%s target=%s model=%s", requestId, target, resolveModel());
            throw new LlmException("LLM call failed", e);
        }
    }

    private String extractContent(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode first = choices.get(0);
                JsonNode messageContent = first.path("message").path("content");
                if (!messageContent.isMissingNode() && !messageContent.isNull()) {
                    return messageContent.asText();
                }
                JsonNode text = first.path("text");
                if (!text.isMissingNode() && !text.isNull()) {
                    return text.asText();
                }
            }
            JsonNode output = root.path("output");
            if (output.isArray() && output.size() > 0) {
                JsonNode first = output.get(0);
                JsonNode content = first.path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
            }
            return "No se pudo interpretar la respuesta del LLM (fallback). raw=" + truncate(raw);
        } catch (Exception e) {
            return "No se pudo interpretar la respuesta del LLM (fallback). raw=" + truncate(raw);
        }
    }

    private String resolveModel() {
        return model
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(DEFAULT_MODEL);
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncate(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() > 280 ? value.substring(0, 280) + "…" : value;
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
}
