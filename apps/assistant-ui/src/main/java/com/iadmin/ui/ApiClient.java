package com.iadmin.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iadmin.ui.model.ChatRequest;
import com.iadmin.ui.model.Report;
import com.iadmin.ui.model.RequestStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApiClient {

    private static final Logger LOGGER = Logger.getLogger("UI.ApiClient");

    @ConfigProperty(name = "assistant.api.url")
    String apiUrl;

    @Inject
    ObjectMapper mapper;

    @PostConstruct
    void init() {
        if (apiUrl == null || apiUrl.isBlank()) {
            LOGGER.error("[INIT] assistant.api.url no configurada — no se podrán realizar solicitudes a la API");
        } else {
            LOGGER.infov("[INIT] assistant.api.url configurada -> {0}", apiUrl);
        }
    }

    public FetchResult fetchReport(String namespace, int minutes) {
        String requestId = UUID.randomUUID().toString();
        List<String> steps = List.of(
                "Validar parámetros",
                "Construir solicitud",
                "Invocar Assistant API",
                "Procesar respuesta");
        List<String> completed = new ArrayList<>();
        List<String> pending = new ArrayList<>(steps);
        List<String> failed = new ArrayList<>();
        String currentStep = steps.getFirst();
        Instant requestStart = Instant.now();

        try {
            if (apiUrl == null || apiUrl.isBlank()) {
                pending.remove(currentStep);
                failed.add(currentStep);
                RequestStatus status = RequestStatus.failure(
                        requestId,
                        "No hay una URL configurada para la Assistant API.",
                        completed,
                        failed,
                        pending);
                throw new ExternalServiceException("assistant.api.url no configurada", status);
            }
            pending.remove(currentStep);
            completed.add(currentStep);

            Instant now = Instant.now();
            Instant from = now.minus(Duration.ofMinutes(minutes));

            ChatRequest request = new ChatRequest(
                    "analiza ambiente",
                    namespace,
                    from.toString(),
                    now.toString(),
                    null);

            currentStep = "Construir solicitud";
            pending.remove(currentStep);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(apiUrl + "/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            completed.add(currentStep);

            currentStep = "Invocar Assistant API";
            pending.remove(currentStep);
            LOGGER.infov(
                    "[COMM-START] requestId={0} target={1}/chat namespace={2} from={3} to={4}",
                    requestId,
                    apiUrl,
                    namespace,
                    from,
                    now);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long durationMs = Duration.between(requestStart, Instant.now()).toMillis();
            LOGGER.infov(
                    "[COMM-END] requestId={0} status={1} durationMs={2}",
                    requestId,
                    response.statusCode(),
                    durationMs);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                failed.add(currentStep);
                RequestStatus status = RequestStatus.failure(
                        requestId,
                        "La Assistant API respondió con estado " + response.statusCode() + ".",
                        completed,
                        failed,
                        pending);
                throw new ExternalServiceException(
                        "Assistant API returned status " + response.statusCode(),
                        status);
            }
            completed.add(currentStep);

            currentStep = "Procesar respuesta";
            pending.remove(currentStep);
            JsonNode root = mapper.readTree(response.body());
            Report report = null;
            if (root.has("reportJson") && !root.get("reportJson").isNull()) {
                report = mapper.treeToValue(root.get("reportJson"), Report.class);
            }
            completed.add(currentStep);
            RequestStatus status = RequestStatus.success(requestId, completed);
            return new FetchResult(report, status);
        } catch (ExternalServiceException e) {
            LOGGER.errorf(
                    "[COMM-ERROR] requestId=%s url=%s detalle=%s", requestId, apiUrl, e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.add(currentStep);
            RequestStatus status = RequestStatus.failure(
                    requestId,
                    "La solicitud al servicio fue interrumpida.",
                    completed,
                    failed,
                    pending);
            LOGGER.errorf(e, "[COMM-ERROR] requestId=%s url=%s La llamada fue interrumpida", requestId, apiUrl);
            throw new ExternalServiceException("Assistant API call interrupted", status, e);
        } catch (Exception e) {
            failed.add(currentStep);
            RequestStatus status = RequestStatus.failure(
                    requestId,
                    "Ocurrió un error al obtener el reporte.",
                    completed,
                    failed,
                    pending);
            LOGGER.errorf(
                    e,
                    "[COMM-ERROR] requestId=%s url=%s parametros={namespace=%s,minutes=%s}",
                    requestId,
                    apiUrl,
                    namespace,
                    minutes);
            throw new ExternalServiceException("Failed to fetch report", status, e);
        }
    }

    public record FetchResult(Report report, RequestStatus status) {
    }
}
