package com.nabd.hms.billing.gst;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** FIN-011: the GSTR-1 friendly export of one month. reports:export and the Owner role; 422 PERIOD_OPEN while the month is running. */
@RestController
@RequestMapping("/v1/billing")
class Gstr1Controller {

    private final Gstr1Service service;

    Gstr1Controller(Gstr1Service service) {
        this.service = service;
    }

    /** period = yyyy-MM (a finished month of the clinic's calendar), format = xlsx or json. */
    @GetMapping("/exports/gstr1")
    @PreAuthorize("hasAuthority('reports:export')")
    Gstr1ExportResponse gstr1(@CurrentStaff UUID staffId, @RequestParam String period, @RequestParam(defaultValue = "xlsx") String format,
                              HttpServletRequest http) {
        return service.export(staffId, RequestMeta.clientIp(http), period, format);
    }
}
