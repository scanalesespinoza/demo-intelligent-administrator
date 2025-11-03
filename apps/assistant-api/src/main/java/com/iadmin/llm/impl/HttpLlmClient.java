package com.iadmin.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iadmin.llm.LlmClient;
import com.iadmin.llm.LlmException;
import com.iadmin.report.Report;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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

    private volatile ChatLanguageModel chatModel;

    @PostConstruct
    void init() {
        LOGGER.infov(
                "[INIT] HttpLlmClient listo. endpoint={0} model={1} apiKeyConfigurada={2}",
                endpoint,
                resolveModel(),
                apiKey.map(value -> !value.isBlank()).orElse(false));
        this.chatModel = buildChatModel();
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
            var prompt = buildPrompt(prettyReport);
            LOGGER.infov(
                    "[COMM-START] requestId={0} target={1} model={2}",
                    requestId,
                    target,
                    resolveModel());
            String response = chatLanguageModel().generate(prompt);
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            LOGGER.infov(
                    "[COMM-END] requestId={0} target={1} durationMs={2}",
                    requestId,
                    target,
                    durationMs);
            return extractContent(response);
        } catch (Exception e) {
            LOGGER.errorf(e, "[COMM-ERROR] requestId=%s target=%s model=%s", requestId, target, resolveModel());
            throw new LlmException("LLM call failed", e);
        }
    }

    String buildPrompt(String prettyReport) {
        return SYSTEM_PROMPT
                + "\n\n"
                + "report:\n"
                + "```json\n"
                + prettyReport
                + "\n```";
    }

    private String extractContent(String response) {
        return Optional.ofNullable(response)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse("No se pudo interpretar la respuesta del LLM (fallback).");
    }

    private String resolveModel() {
        return model
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(DEFAULT_MODEL);
    }

    private ChatLanguageModel buildChatModel() {
        URI target = URI.create(endpoint.trim());
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(resolveModel())
                .baseUrl(normalizeBaseUrl(target))
                .temperature(0.2d)
                .maxTokens(Integer.valueOf(800));

        apiKey.map(String::trim)
                .filter(value -> !value.isEmpty())
                .ifPresent(builder::apiKey);

        return builder.build();
    }

    private ChatLanguageModel chatLanguageModel() {
        ChatLanguageModel current = this.chatModel;
        if (current == null) {
            synchronized (this) {
                if (chatModel == null) {
                    chatModel = buildChatModel();
                }
                current = chatModel;
            }
        }
        return current;
    }

    private String normalizeBaseUrl(URI target) {
        String value = target.toString().trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    void setChatModel(ChatLanguageModel chatLanguageModel) {
        this.chatModel = chatLanguageModel;
    }
}
