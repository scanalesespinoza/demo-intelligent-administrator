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
import jakarta.ws.rs.core.Response;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.TemplateException;
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
    public Response index(
            @QueryParam("ns") String namespace,
            @QueryParam("minutes") @DefaultValue("15") int minutes) {
        String effectiveNamespace = (namespace == null || namespace.isBlank()) ? "demo-int-admin" : namespace;
        LOGGER.infov("[UI-REQUEST] namespace={0} minutes={1}", effectiveNamespace, minutes);
        TemplateInstance view;
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
                view = Templates.index(effectiveNamespace, null, status);
            } else {
                LOGGER.infov(
                        "[UI-REQUEST-SUCCESS] namespace={0} requestId={1}",
                        effectiveNamespace,
                        result.status().requestId());
                view = Templates.index(effectiveNamespace, result.report(), result.status());
            }
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
            view = Templates.index(effectiveNamespace, null, status);
        }
        return renderTemplate(view, effectiveNamespace);
    }

    private Response renderTemplate(TemplateInstance template, String namespace) {
        try {
            String html = template.render();
            return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
        } catch (TemplateException e) {
            LOGGER.errorf(e, "[UI-TEMPLATE-ERROR] namespace=%s mensaje=%s", namespace, e.getMessage());
            String fallback = "<!DOCTYPE html>\n"
                    + "<html lang=\"es\">\n"
                    + "<head><meta charset=\"UTF-8\"><title>Error</title></head>\n"
                    + "<body><h3>Error al renderizar la vista</h3><p>"
                    + e.getMessage()
                    + "</p></body></html>";
            return Response.serverError()
                    .type(MediaType.TEXT_HTML_TYPE)
                    .entity(fallback)
                    .build();
        }
    }
}
