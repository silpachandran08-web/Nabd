package com.nabd.hms.shared.telemetry;

import com.nabd.hms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** OPS-028: in-region sink for web vitals and client errors. Only kind, metric name, route path (query and fragment cut),
 * a numeric value and the stack hash are kept; the free-text message is accepted but never stored or logged.
 * Per-session throttling is the generic PLT-008 per-user bucket (429 rate_limited). */
@RestController
public class ClientTelemetryController {

    static final int MAX_BODY_BYTES = 4096;
    private static final Logger log = LoggerFactory.getLogger(ClientTelemetryController.class);

    public record Report(@NotBlank @Pattern(regexp = "vital|error") String kind,
                         @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.:-]+") String metric,
                         Double value,
                         @NotBlank @Size(max = 200) String route,
                         @Size(max = 500) String message,
                         @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9]*") String stackHash) {
    }

    @PostMapping("/v1/client-telemetry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("isAuthenticated()")
    public void ingest(@Valid @RequestBody Report r, HttpServletRequest http) {
        if (http.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", "Telemetry report too large", "Body over " + MAX_BODY_BYTES + " bytes");
        }
        String path = r.route().split("[?#]", 2)[0];
        MDC.put("reasonCode", r.kind().equals("error") ? "CLIENT_ERROR" : "CLIENT_VITAL");
        try {
            log.info("client telemetry kind={} metric={} route={} value={} stackHash={}", r.kind(), r.metric(), path, r.value(), r.stackHash());
        } finally {
            MDC.remove("reasonCode");
        }
    }
}
