package com.iadmin.api;

import com.iadmin.correlator.CorrelationService;
import com.iadmin.report.Report;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/report")
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource {

    @Inject
    CorrelationService correlator;

    @ConfigProperty(name = "iadmin.window.default-minutes", defaultValue = "15")
    int defaultWindowMin;

    @GET
    @Path("/raw")
    public Report raw(@QueryParam("ns") String namespace,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("topN") Integer topNParam) {
        String ns = namespace == null || namespace.isBlank() ? "demo-int-admin" : namespace;
        Instant toInstant = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
        Instant fromInstant = from == null || from.isBlank()
                ? toInstant.minus(Duration.ofMinutes(defaultWindowMin))
                : Instant.parse(from);
        int topN = Optional.ofNullable(topNParam).orElse(5);
        return correlator.analyze(ns, fromInstant, toInstant, topN);
    }
}
