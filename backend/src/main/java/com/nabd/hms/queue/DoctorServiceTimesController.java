package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ACC-016: consult-time summary per visit type. Owner (reports:view) or the doctor looking at their own figures. */
@RestController
public class DoctorServiceTimesController {

    @Schema(name = "ServiceTimeItem")
    public record Item(String visitType, int n, double medianMin, double p80Min, String priorSource) {
    }

    @Schema(name = "ServiceTimesResponse")
    public record Response(List<Item> items) {
    }

    private final ServiceTimeService service;

    DoctorServiceTimesController(ServiceTimeService service) {
        this.service = service;
    }

    @GetMapping("/v1/doctors/{id}/service-times")
    @PreAuthorize("isAuthenticated()")
    public Response get(@PathVariable UUID id, Authentication auth) {
        boolean owner = auth.getAuthorities().stream().anyMatch(a -> "reports:view".equals(a.getAuthority()));
        if (!owner && !id.toString().equals(auth.getName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You may only see your own consult times.");
        }
        return new Response(service.summary(id).stream().filter(r -> r.medianS() != null)
                .map(r -> new Item(r.visitType(), r.n(), r.medianS() / 60.0, r.p80S() / 60.0, r.priorSource())).toList());
    }
}
