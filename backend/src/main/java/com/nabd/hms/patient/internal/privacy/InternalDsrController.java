package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PLT-047: the WhatsApp bot's intake (service identity, tenant in the path like every other Edge call; the spec's
 * /internal/v1/privacy/requests with tenantId in the body would skip the path's tenant allow-list). The Edge names the person the
 * request is about and the phone it came from; Core decides whether that phone may ask (DsrService.createFromChat).
 * 404 PATIENT_NOT_FOUND, 403 PROXY_NOT_APPROVED / PHONE_NOT_VERIFIED, 409 OPEN_REQUEST_EXISTS.
 */
@RestController
class InternalDsrController {

    @Schema(name = "InternalDsrRequestCreate")
    record CreateBody(@NotBlank @Size(max = 64) String personRef, @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String phoneHash,
                      @NotBlank @Pattern(regexp = "access|correction|erasure") String type) {
    }

    private final TenantScope tenantScope;
    private final DsrService service;

    InternalDsrController(TenantScope tenantScope, DsrService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/privacy/requests")
    @ResponseStatus(HttpStatus.CREATED)
    DsrService.Created create(@PathVariable UUID tid, @Valid @RequestBody CreateBody b) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return tenantScope.run(tid, () -> service.createFromChat(edge.serviceId(), b.personRef(), b.phoneHash(), b.type())).orThrow();
    }
}
