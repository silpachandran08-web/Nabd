package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.api.AbdmVerification;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** IDC-016: a doctor's HPR id, verified against the Health Professional Registry, stored on the staff row. Needs ABDM enabled for the clinic. */
@RestController
class StaffHprController {

    @Schema(name = "StaffHprRequest")
    record Request(@NotBlank @Pattern(regexp = "^[A-Za-z0-9@._-]{3,60}$") String hprId) {
    }

    @Schema(name = "StaffHpr")
    record Response(String staffId, String hprId, boolean hprVerified) {
    }

    private final AbdmVerification abdm;
    private final StaffHprService writer;

    StaffHprController(AbdmVerification abdm, StaffHprService writer) {
        this.abdm = abdm;
        this.writer = writer;
    }

    @PutMapping("/v1/staff/{id}/hpr")
    @PreAuthorize("hasAuthority('staff:edit')")
    Response put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Request req) {
        if (!abdm.hprIdExists(req.hprId())) { // remote call, outside any transaction
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "hpr-not-found", "Professional not found",
                    "This HPR id is not in the Health Professional Registry.");
        }
        writer.store(id, req.hprId(), UUID.fromString(jwt.getSubject()));
        return new Response(id.toString(), req.hprId(), true);
    }
}
