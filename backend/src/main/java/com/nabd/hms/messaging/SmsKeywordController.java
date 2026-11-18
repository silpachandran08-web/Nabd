package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * COM-028: the Comms Gateway relays an inbound SMS keyword (STOP) to Core's consent API; Core classifies it with the COM-002 matcher and
 * withdraws marketing ({@link OptOutService#handleSms}). Same service token as the COM-008 consent export. tenantId = the clinic of the
 * latest SMS to the number (null when the gateway never sent it one). Idempotent: a repeated STOP answers confirm=false.
 */
@RestController
class SmsKeywordController {

    @Schema(name = "SmsKeywordRequest")
    record Request(@NotNull @Pattern(regexp = "^[1-9][0-9]{7,14}$") String phone, @NotNull @Size(max = 64) String text, UUID tenantId,
                   @NotNull @PositiveOrZero Long inboundRef) {
    }

    @Schema(name = "SmsKeywordResponse")
    record Response(boolean optedOut, boolean confirm) {
    }

    private final OptOutService optOut;
    private final TenantScope tenantScope;
    private final byte[] token;

    SmsKeywordController(OptOutService optOut, TenantScope tenantScope, @Value("${app.internal.comms-token:}") String token) {
        this.optOut = optOut;
        this.tenantScope = tenantScope;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/v1/consents:sms-keyword")
    Response keyword(@RequestHeader(value = "X-Internal-Token", required = false) String presented, @Valid @RequestBody Request req) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Internal service identity required.");
        }
        OptOutService.SmsResult r = req.tenantId() == null
                ? optOut.handleSms(null, req.phone(), req.text(), req.inboundRef())
                : tenantScope.run(req.tenantId(), () -> optOut.handleSms(req.tenantId(), req.phone(), req.text(), req.inboundRef()));
        return new Response(r.optedOut(), r.confirm());
    }
}
