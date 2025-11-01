package com.iadmin.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iadmin.llm.LlmClient;
import com.iadmin.report.Report;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpLlmClient implements LlmClient {

    static final String SYSTEM_PROMPT = """
            Eres iAdmin. Recibirás un JSON `report` con hallazgos técnicos.
            - NO inventes datos. Solo explica lo que contiene `report`.
            - Escribe en español, claro y conciso.
            - Secciones: Resumen, Servicios afectados (con síntomas), Evidencia clave (eventos/logs), Posibles causas, Recomendaciones.
            - Si algo falta, dilo explícitamente (“no se encontró evidencia de …”).
            """;

    @ConfigProperty(name = "llm.endpoint")
    String endpoint;

    @ConfigProperty(name = "llm.model", defaultValue = "granite-7b-instruct")
    String model;

    @ConfigProperty(name = "llm.api-key")
    Optional<String> apiKey = Optional.empty();

    @Inject
    ObjectMapper mapper;

    private volatile HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String redactReport(Report report) {
        try {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("llm.endpoint no configurado");
            }
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
                    "model", model,
                    "messages", List.of(sys, user),
                    "temperature", 0.2,
                    "max_tokens", 800
            );

            var builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

            apiKey.map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .ifPresent(value -> builder.header("Authorization", "Bearer " + value));

            var request = builder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("LLM call failed with status " + response.statusCode());
            }
            return extractContent(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM call interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed", e);
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
