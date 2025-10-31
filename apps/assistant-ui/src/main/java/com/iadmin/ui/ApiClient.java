package com.iadmin.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iadmin.ui.model.ChatRequest;
import com.iadmin.ui.model.Report;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ApiClient {

    @ConfigProperty(name = "assistant.api.url")
    String apiUrl;

    @Inject
    ObjectMapper mapper;

    public Report fetchReport(String namespace, int minutes) {
        try {
            Instant now = Instant.now();
            Instant from = now.minus(Duration.ofMinutes(minutes));

            ChatRequest request = new ChatRequest(
                    "analiza ambiente",
                    namespace,
                    from.toString(),
                    now.toString(),
                    null);

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(apiUrl + "/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (root.has("reportJson") && !root.get("reportJson").isNull()) {
                return mapper.treeToValue(root.get("reportJson"), Report.class);
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch report", e);
        }
    }
}
