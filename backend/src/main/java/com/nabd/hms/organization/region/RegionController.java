package com.nabd.hms.organization.region;

import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.organization.region.dto.RegionListResponse;
import com.nabd.hms.organization.region.dto.RegionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OPS-061: reference data for the onboarding region picker and any staff screen; staff on /v1/regions, operators on /v1/platform/regions. */
@RestController
// operator tokens open only /v1/platform/** (AccessTokenDecoderConfig), so the same list is served there too
@RequestMapping({"/v1/regions", "/v1/platform/regions"})
class RegionController {

    private final RegionRules regions;

    RegionController(RegionRules regions) {
        this.regions = regions;
    }

    @GetMapping
    public RegionListResponse list() {
        return new RegionListResponse(regions.all().stream().filter(RegionRules.Region::active).map(r -> new RegionResponse(
                r.code(), r.name(), r.currency(), r.minorUnits(), r.taxRegime(), r.standardTaxRate(), r.regulator(),
                r.defaultTimezone(), new RegionResponse.RegionPhoneRules(r.phoneCountryCode(), r.phoneNsnLength()), r.cell())).toList());
    }
}
