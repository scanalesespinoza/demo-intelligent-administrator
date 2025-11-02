package com.iadmin.ui;

import com.iadmin.ui.ApiClient.FetchResult;
import com.iadmin.ui.ExternalServiceException;
import com.iadmin.ui.model.Report;
import com.iadmin.ui.model.RequestStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import java.util.List;
import org.jboss.logging.Logger;

@Path("/")
public class IndexResource {

    private static final Logger LOGGER = Logger.getLogger("UI.IndexResource");

    @Inject
    ApiClient api;

    @CheckedTemplate
    static class Templates {
        public static native TemplateInstance index(String namespace, Report report, RequestStatus status);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(
            @QueryParam("ns") String namespace,
            @QueryParam("minutes") @DefaultValue("15") int minutes) {
        String effectiveNamespace = (namespace == null || namespace.isBlank()) ? "demo-int-admin" : namespace;
        LOGGER.infov("[UI-REQUEST] namespace={0} minutes={1}", effectiveNamespace, minutes);
        try {
            FetchResult result = api.fetchReport(effectiveNamespace, minutes);
            if (result == null) {
                RequestStatus status = RequestStatus.failure(
                        "sin-respuesta",
                        "La Assistant API no devolvió información.",
                        List.of("Validar parámetros"),
                        List.of("Procesar respuesta"),
                        List.of());
                LOGGER.warnf(
                        "[UI-REQUEST-WARN] namespace=%s la respuesta del cliente fue nula", effectiveNamespace);
                return Templates.index(effectiveNamespace, null, status);
            }
            LOGGER.infov(
                    "[UI-REQUEST-SUCCESS] namespace={0} requestId={1}",
                    effectiveNamespace,
                    result.status().requestId());
            return Templates.index(effectiveNamespace, result.report(), result.status());
        } catch (ExternalServiceException e) {
            RequestStatus status = e.status();
            if (status == null) {
                status = RequestStatus.failure(
                        "sin-seguimiento",
                        e.getMessage(),
                        List.of(),
                        List.of("Invocar Assistant API"),
                        List.of());
            }
            LOGGER.errorf(
                    e,
                    "[UI-REQUEST-ERROR] namespace=%s requestId=%s mensaje=%s",
                    effectiveNamespace,
                    status != null ? status.requestId() : "<sin-id>",
                    status != null ? status.message() : e.getMessage());
            return Templates.index(effectiveNamespace, null, status);
        }
    }
}
