package com.nabd.hms.platform.plans;

import com.nabd.hms.platform.plans.dto.PlanCatalogueEntry;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** ADM-034: public plan comparison for sign-up and the owner plan screen. No auth, no tenant data; cacheable at the CDN. */
@RestController
public class PlanCatalogueController {

    private final PlanService service;

    PlanCatalogueController(PlanService service) {
        this.service = service;
    }

    @GetMapping("/v1/plans/catalogue")
    public ResponseEntity<List<PlanCatalogueEntry>> catalogue(@RequestParam(required = false) String market) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(service.catalogue(market));
    }
}
