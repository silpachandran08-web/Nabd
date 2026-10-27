package com.nabd.hms.identity.abdm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nabd.hms.common.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * IDC-018: ABDM Scan &amp; Share callback. Anonymous at the HTTP layer (PublicEndpointsSecurityConfig); the gateway's signed bearer
 * is verified here and anything else is 401. Always 202 for a signed, well-formed request: the token (or "please see the desk")
 * goes back to ABDM through the gateway. The body is never logged.
 */
@RestController
class AbdmShareController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "AbdmShareProfile")
    record ProfileDto(String abhaNumber, String abhaAddress, String name, String gender, Integer yearOfBirth, Integer monthOfBirth,
                      Integer dayOfBirth, String phoneNumber) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "AbdmShareRequest")
    record ShareRequest(String requestId, String hipId, String counter, ProfileDto profile) {
    }

    private final AbdmCallbackAuth auth;
    private final AbdmShareService service;

    AbdmShareController(AbdmCallbackAuth auth, AbdmShareService service) {
        this.auth = auth;
        this.service = service;
    }

    @PostMapping("/abdm/v3/hip/patient/share")
    ResponseEntity<Void> share(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestHeader(value = "X-HIP-ID", required = false) String hipHeader, @RequestBody ShareRequest req) {
        auth.require(authorization);
        String hip = hipHeader != null && !hipHeader.isBlank() ? hipHeader.strip() : req.hipId();
        if (hip == null || hip.isBlank() || req.requestId() == null || req.requestId().isBlank() || req.requestId().length() > 100 || req.profile() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-share", "Invalid share", "requestId, hipId and profile are required.");
        }
        ProfileDto p = req.profile();
        service.handle(hip.strip(), req.requestId().strip(), req.counter(), new AbdmShareService.Profile(p.abhaNumber(), p.abhaAddress(), p.name(),
                p.gender(), p.yearOfBirth(), p.monthOfBirth(), p.dayOfBirth(), p.phoneNumber()));
        return ResponseEntity.accepted().build();
    }
}
