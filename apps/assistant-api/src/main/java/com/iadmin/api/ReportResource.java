package com.iadmin.api;

import com.iadmin.correlator.CorrelationService;
import com.iadmin.report.Report;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/report")
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource {

    private static final Logger LOGGER = Logger.getLogger("API.ReportResource");

    @Inject
    CorrelationService correlator;

    @ConfigProperty(name = "iadmin.window.default-minutes", defaultValue = "15")
    int defaultWindowMin;

    @PostConstruct
    void init() {
        LOGGER.infov(
                "[INIT] ReportResource listo. correlator={0}",
                correlator != null ? correlator.getClass().getSimpleName() : "<null>");
    }

    @GET
    @Path("/raw")
    public Report raw(@QueryParam("ns") String namespace,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("topN") Integer topNParam) {
        String requestId = UUID.randomUUID().toString();
        String ns = namespace == null || namespace.isBlank() ? "demo-int-admin" : namespace;
        Instant toInstant = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
        Instant fromInstant = from == null || from.isBlank()
                ? toInstant.minus(Duration.ofMinutes(defaultWindowMin))
                : Instant.parse(from);
        int topN = Optional.ofNullable(topNParam).orElse(5);
        LOGGER.infov(
                "[COMM-START] requestId={0} target=CorrelationService action=analyze ns={1} from={2} to={3} topN={4}",
                requestId,
                ns,
                fromInstant,
                toInstant,
                topN);
        Report report = correlator.analyze(ns, fromInstant, toInstant, topN);
        LOGGER.infov(
                "[COMM-END] requestId={0} target=CorrelationService action=analyze findings={1}",
                requestId,
                report.findings().size());
        return report;
    }
}
