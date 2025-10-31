package com.iadmin.api;

import com.iadmin.audit.AuditService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditService audit;

    @GET
    @Path("/export")
    public List<AuditService.AuditEntry> export() {
        return audit.export();
    }
}
