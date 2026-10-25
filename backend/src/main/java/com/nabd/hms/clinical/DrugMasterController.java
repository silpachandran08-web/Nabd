package com.nabd.hms.clinical;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CLN-019: read side of the regional drug master (reference data, not patient data). */
@RestController
class DrugMasterController {

    private final DrugMasterService service;

    DrugMasterController(DrugMasterService service) {
        this.service = service;
    }

    @GetMapping("/v1/clinical/drugs/{drugId}")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view') or hasAuthority('pharmacy:view')")
    public DrugMasterService.DetailView detail(@PathVariable String drugId) {
        return service.detail(drugId);
    }

    @GetMapping("/v1/clinical/drugs")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view') or hasAuthority('pharmacy:view')")
    public List<DrugMasterService.SummaryView> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        return service.search(q, Math.max(1, Math.min(limit, 100)));
    }
}
