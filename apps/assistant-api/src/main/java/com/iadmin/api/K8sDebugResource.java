package com.iadmin.api;

import com.iadmin.config.ConfigConnector;
import com.iadmin.k8s.K8sConnector;
import com.iadmin.model.ConfigSnapshot;
import com.iadmin.model.DeploymentSummary;
import com.iadmin.model.K8sEvent;
import com.iadmin.model.LogChunk;
import com.iadmin.model.PodSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Path("/v1/k8s")
@Produces(MediaType.APPLICATION_JSON)
public class K8sDebugResource {

    @Inject
    K8sConnector k8s;

    @Inject
    ConfigConnector cfg;

    @GET
    @Path("/deployments")
    public List<DeploymentSummary> deployments(@QueryParam("ns") String namespace) {
        return k8s.listDeployments(namespace);
    }

    @GET
    @Path("/pods")
    public List<PodSummary> pods(@QueryParam("ns") String namespace) {
        return k8s.listPods(namespace, Map.of());
    }

    @GET
    @Path("/events")
    public List<K8sEvent> events(@QueryParam("ns") String namespace,
                                 @QueryParam("from") String from,
                                 @QueryParam("to") String to) {
        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);
        return k8s.getEvents(namespace, fromInstant, toInstant);
    }

    @GET
    @Path("/logs")
    public List<LogChunk> logs(@QueryParam("ns") String namespace,
                               @QueryParam("pod") String pod,
                               @QueryParam("container") String container,
                               @QueryParam("from") String from,
                               @QueryParam("to") String to,
                               @QueryParam("tail") @DefaultValue("200") int tail) {
        return k8s.getPodLogs(namespace, pod, container, parseInstant(from), parseInstant(to), tail);
    }

    @GET
    @Path("/config")
    public ConfigSnapshot config(@QueryParam("ns") String namespace,
                                 @QueryParam("deploy") String deployment) {
        return cfg.getDeploymentConfig(namespace, deployment);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
