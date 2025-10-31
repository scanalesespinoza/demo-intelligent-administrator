package com.iadmin.ui;

import com.iadmin.ui.model.Report;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@Path("/")
public class IndexResource {

    @Inject
    ApiClient api;

    @CheckedTemplate
    static class Templates {
        public static native TemplateInstance index(String namespace, Report report);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(
            @QueryParam("ns") String namespace,
            @QueryParam("minutes") @DefaultValue("15") int minutes) {
        String effectiveNamespace = (namespace == null || namespace.isBlank()) ? "demo-int-admin" : namespace;
        Report report = api.fetchReport(effectiveNamespace, minutes);
        return Templates.index(effectiveNamespace, report);
    }
}
