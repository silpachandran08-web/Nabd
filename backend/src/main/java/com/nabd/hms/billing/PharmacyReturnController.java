package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.PharmacyReturnDtos;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.PharmacyMode;
import com.nabd.hms.pharmacy.RequiresPharmacyMode;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PHL-011: counter returns. Same grant as a FIN-013 refund request (billing:edit); the approval stays billing:approve, by someone else. */
@RestController
@RequestMapping("/v1/pharmacy/returns")
@RequiresPharmacyMode(PharmacyMode.HYBRID) // a counter sale exists in Hybrid and In-house only (PHL-004)
public class PharmacyReturnController {

    private final PharmacyReturnService service;

    PharmacyReturnController(PharmacyReturnService service) {
        this.service = service;
    }

    /** The bill to take a return against, by id or by its printed number, with what each line still allows. */
    @GetMapping("/bill")
    @PreAuthorize("hasAuthority('billing:edit')")
    public PharmacyReturnDtos.Bill bill(@RequestParam(required = false) UUID invoiceId, @RequestParam(required = false) String number) {
        return service.bill(invoiceId, number);
    }

    @Idempotent
    @PostMapping
    @PreAuthorize("hasAuthority('billing:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyReturnDtos.ReturnResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PharmacyReturnDtos.ReturnRequest req,
                                                    HttpServletRequest http) {
        return service.create(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req);
    }
}
