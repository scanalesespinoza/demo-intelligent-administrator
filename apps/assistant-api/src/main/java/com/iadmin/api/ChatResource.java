package com.iadmin.api;

import com.iadmin.audit.AuditService;
import com.iadmin.correlator.CorrelationService;
import com.iadmin.llm.LlmClient;
import com.iadmin.report.Report;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

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

    @POST
    public ChatResponse chat(ChatRequest req) {
        ChatRequest safeReq = req == null ? new ChatRequest(null, null, null, null, null) : req;
        var ns = Optional.ofNullable(safeReq.namespace()).orElse("demo-int-admin");

        Instant to = Optional.ofNullable(safeReq.to()).map(Instant::parse).orElse(Instant.now());
        Instant from = Optional.ofNullable(safeReq.from()).map(Instant::parse)
                .orElse(to.minus(Duration.ofMinutes(defaultWindowMin)));

        int topN = Optional.ofNullable(safeReq.topN()).orElse(5);

        Report report = correlator.analyze(ns, from, to, topN);
        String narrative = llm.redactReport(report);

        audit.add(Map.of(
                "ts", Instant.now().toString(),
                "ns", ns,
                "from", from,
                "to", to,
                "findings", report.findings().size(),
                "causes", report.findings().stream().map(Report.Finding::causeLikely).distinct().toList()));

        return new ChatResponse(report, narrative);
    }
}
