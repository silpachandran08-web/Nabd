package com.nabd.hms.pharmacy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** PHL-001: the counter's "Link prescription" picker. POST so the phone number never sits in a URL. */
@RestController
@RequestMapping("/v1/pharmacy/rx-lines")
public class RxLineController {

    public record RxLineSearchRequest(@NotBlank String phone) {
    }

    private final RxGate rxGate;

    RxLineController(RxGate rxGate) {
        this.rxGate = rxGate;
    }

    @PostMapping("/search")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('billing:create')")
    public List<RxGate.RxLineResponse> search(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RxLineSearchRequest req) {
        return rxGate.searchByPhone(UUID.fromString(jwt.getClaimAsString("tenantId")), req.phone());
    }
}
