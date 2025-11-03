package com.iadmin.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iadmin.llm.LlmException;
import com.iadmin.report.Report;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLlmClientTest {

    HttpLlmClient client;
    StubChatLanguageModel chatModel;

    @BeforeEach
    void setUp() {
        client = new HttpLlmClient();
        client.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client.model = Optional.of("test-model");
        client.endpoint = "http://localhost/test";
        client.apiKey = Optional.empty();
        chatModel = new StubChatLanguageModel("Hola mundo");
        client.setChatModel(chatModel);
    }

    @Test
    void extractContentFromChatModel() {
        Report report = new Report(
                new Report.TimeWindow(Instant.now(), Instant.now().plus(Duration.ofMinutes(5))),
                List.of(),
                "",
                List.of());

        String narrative = client.redactReport(report);
        assertEquals("Hola mundo", narrative);
    }

    @Test
    void buildPromptFollowingGraniteCompletionsFormat() {
        Report report = new Report(
                new Report.TimeWindow(Instant.parse("2024-08-15T10:00:00Z"), Instant.parse("2024-08-15T10:15:00Z")),
                List.of(new Report.Finding(
                        "svc-a",
                        "ns-1",
                        "degraded",
                        List.of("symptom"),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        "cause",
                        0.9,
                        3)),
                "Resumen",
                List.of("recommendation"));

        client.redactReport(report);

        assertNotNull(chatModel.lastPrompt);
        assertTrue(chatModel.lastPrompt.contains(HttpLlmClient.SYSTEM_PROMPT));
        assertTrue(chatModel.lastPrompt.contains("```json"));
        assertTrue(chatModel.lastPrompt.contains("\"svc-a\""));
    }

    @Test
    void failWhenEndpointMissing() {
        client.endpoint = "   ";

        Report report = new Report(
                new Report.TimeWindow(Instant.now(), Instant.now().plus(Duration.ofMinutes(5))),
                List.of(),
                "",
                List.of());

        LlmException ex = assertThrows(LlmException.class, () -> client.redactReport(report));
        assertEquals("llm.endpoint no configurado", ex.getMessage());
    }

    @Test
    void fallbackMessageWhenResponseEmpty() {
        chatModel.response = "   ";

        Report report = new Report(
                new Report.TimeWindow(Instant.now(), Instant.now().plus(Duration.ofMinutes(5))),
                List.of(),
                "",
                List.of());

        String narrative = client.redactReport(report);
        assertEquals("No se pudo interpretar la respuesta del LLM (fallback).", narrative);
    }

    static class StubChatLanguageModel implements ChatLanguageModel {

        String lastPrompt;
        String response;

        StubChatLanguageModel(String response) {
            this.response = response;
        }

        @Override
        public String generate(String prompt) {
            this.lastPrompt = prompt;
            return response;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            throw new UnsupportedOperationException("Not required for this test");
        }
    }
}
