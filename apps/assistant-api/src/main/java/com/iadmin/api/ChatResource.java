package com.iadmin.api;

import com.iadmin.audit.AuditService;
import com.iadmin.correlator.CorrelationService;
import com.iadmin.llm.LlmClient;
import com.iadmin.llm.LlmException;
import com.iadmin.report.Report;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOGGER = Logger.getLogger("API.ChatResource");

    public static record ChatRequest(String message, String namespace, String from, String to, Integer topN) {
    }

    public static record ChatResponse(Report reportJson, String narrative) {
    }

    @Inject
    CorrelationService correlator;

    @Inject
    LlmClient llm;

    @Inject
    AuditService audit;

    @ConfigProperty(name = "iadmin.window.default-minutes", defaultValue = "15")
    int defaultWindowMin;

    @PostConstruct
    void init() {
        LOGGER.infov(
                "[INIT] ChatResource listo. correlator={0}, llm={1}, audit={2}",
                correlator != null ? correlator.getClass().getSimpleName() : "<null>",
                llm != null ? llm.getClass().getSimpleName() : "<null>",
                audit != null ? audit.getClass().getSimpleName() : "<null>");
    }

    @POST
    public ChatResponse chat(ChatRequest req) {
        String requestId = UUID.randomUUID().toString();
        List<String> steps = List.of(
                "Validar solicitud",
                "Generar informe correlacionado",
                "Solicitar narrativa al LLM",
                "Registrar auditoría");
        List<String> pending = new ArrayList<>(steps);
        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        String currentStep = steps.getFirst();

        try {
            ChatRequest safeReq = req == null ? new ChatRequest(null, null, null, null, null) : req;
            pending.remove(currentStep);
            var ns = Optional.ofNullable(safeReq.namespace()).orElse("demo-int-admin");

            Instant to = Optional.ofNullable(safeReq.to()).map(Instant::parse).orElse(Instant.now());
            Instant from = Optional.ofNullable(safeReq.from()).map(Instant::parse)
                    .orElse(to.minus(Duration.ofMinutes(defaultWindowMin)));

            int topN = Optional.ofNullable(safeReq.topN()).orElse(15);
            if (topN < 1) {
                topN = 1;
            } else if (topN > 15) {
                topN = 15;
            }
            completed.add(currentStep);

            currentStep = "Generar informe correlacionado";
            pending.remove(currentStep);
            Instant correlationStart = Instant.now();
            LOGGER.infov(
                    "[COMM-START] requestId={0} target=CorrelationService ns={1} from={2} to={3} topN={4}",
                    requestId,
                    ns,
                    from,
                    to,
                    topN);
            Report report = correlator.analyze(ns, from, to, topN);
            LOGGER.infov(
                    "[COMM-END] requestId={0} target=CorrelationService durationMs={1} findings={2}",
                    requestId,
                    java.time.Duration.between(correlationStart, Instant.now()).toMillis(),
                    report.findings().size());
            completed.add(currentStep);

            currentStep = "Solicitar narrativa al LLM";
            pending.remove(currentStep);
            Instant llmStart = Instant.now();
            LOGGER.infov(
                    "[COMM-START] requestId={0} target=LLM findings={1}",
                    requestId,
                    report.findings().size());
            String narrative = llm.redactReport(report);
            LOGGER.infov(
                    "[COMM-END] requestId={0} target=LLM durationMs={1}",
                    requestId,
                    java.time.Duration.between(llmStart, Instant.now()).toMillis());
            completed.add(currentStep);

            currentStep = "Registrar auditoría";
            pending.remove(currentStep);
            audit.add(Map.of(
                    "ts", Instant.now().toString(),
                    "ns", ns,
                    "from", from,
                    "to", to,
                    "findings", report.findings().size(),
                    "causes", report.findings().stream().map(Report.Finding::causeLikely).distinct().toList()));
            completed.add(currentStep);

            LOGGER.infov(
                    "[CHAT-SUCCESS] requestId={0} ns={1} findings={2}",
                    requestId,
                    ns,
                    report.findings().size());
            return new ChatResponse(report, narrative);
        } catch (LlmException e) {
            failed.add(currentStep);
            Response failure = Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of(
                            "mensaje", e.getMessage(),
                            "requestId", requestId,
                            "pasosCompletados", completed,
                            "pasosFallidos", failed,
                            "pasosPendientes", pending))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
            LOGGER.errorf(
                    e,
                    "[COMM-ERROR] requestId=%s target=LLM pasosCompletados=%s pasosPendientes=%s",
                    requestId,
                    completed,
                    pending);
            throw new WebApplicationException(e, failure);
        } catch (WebApplicationException e) {
            failed.add(currentStep);
            LOGGER.errorf(
                    e,
                    "[CHAT-ERROR] requestId=%s pasosCompletados=%s pasosPendientes=%s",
                    requestId,
                    completed,
                    pending);
            throw e;
        } catch (RuntimeException e) {
            failed.add(currentStep);
            Response failure = Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "mensaje", "No se pudo completar el análisis.",
                            "requestId", requestId,
                            "pasosCompletados", completed,
                            "pasosFallidos", failed,
                            "pasosPendientes", pending))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
            LOGGER.errorf(
                    e,
                    "[CHAT-ERROR] requestId=%s pasosCompletados=%s pasosPendientes=%s",
                    requestId,
                    completed,
                    pending);
            throw new WebApplicationException(e, failure);
        }
    }
}
