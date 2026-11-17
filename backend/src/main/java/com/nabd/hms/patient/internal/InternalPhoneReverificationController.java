package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** COM-041: the Patient Access Edge reads and reports the re-verification of a WhatsApp number (PhoneReverificationService). */
@RestController
class InternalPhoneReverificationController {

    @Schema(name = "PhoneReverificationState")
    record State(String state) {
    }

    @Schema(name = "PhoneReverificationStep")
    record Step(@NotNull @Pattern(regexp = "flagged|failed|locked|verified") String step,
                @Pattern(regexp = "changed_number|wa_id_changed|silence|desk_change") String reason,
                @Pattern(regexp = "otp\\+dob") String method, @Min(1) Integer attempt, Instant lockedUntil) {
    }

    @Schema(name = "PhoneSecondFactorRequest")
    record Check(@NotNull @PastOrPresent LocalDate dob) {
    }

    @Schema(name = "PhoneSecondFactorResponse")
    record Match(boolean match) {
    }

    private final TenantScope tenantScope;
    private final PhoneReverificationService reverifications;

    InternalPhoneReverificationController(TenantScope tenantScope, PhoneReverificationService reverifications) {
        this.tenantScope = tenantScope;
        this.reverifications = reverifications;
    }

    @GetMapping("/internal/v1/tenants/{tid}/households/{phoneHash}/reverification")
    State state(@PathVariable UUID tid, @PathVariable String phoneHash) {
        caller(tid, phoneHash);
        return tenantScope.run(tid, () -> new State(reverifications.state(tid, phoneHash)));
    }

    @PostMapping("/internal/v1/tenants/{tid}/households/{phoneHash}/reverification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@PathVariable UUID tid, @PathVariable String phoneHash, @Valid @RequestBody Step req) {
        ServiceIdentity edge = caller(tid, phoneHash);
        boolean complete = switch (req.step()) {
            case "flagged" -> req.reason() != null;
            case "failed" -> req.attempt() != null;
            case "locked" -> req.attempt() != null && req.lockedUntil() != null;
            default -> req.method() != null;
        };
        if (!complete) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "flagged needs reason, failed needs attempt, locked needs attempt and lockedUntil, verified needs method.");
        }
        tenantScope.run(tid, () -> reverifications.report(tid, edge.serviceId(), phoneHash, req.step(), req.reason(), req.method(), req.attempt(), req.lockedUntil()));
    }

    @PostMapping("/internal/v1/tenants/{tid}/households/{phoneHash}/reverification/check")
    Match check(@PathVariable UUID tid, @PathVariable String phoneHash, @Valid @RequestBody Check req) {
        ServiceIdentity edge = caller(tid, phoneHash);
        return tenantScope.run(tid, () -> new Match(reverifications.dobMatches(tid, edge.serviceId(), phoneHash, req.dob())));
    }

    private static ServiceIdentity caller(UUID tid, String phoneHash) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        if (!phoneHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "phoneHash must be 64 lowercase hex characters (SHA-256).");
        }
        return edge;
    }
}
