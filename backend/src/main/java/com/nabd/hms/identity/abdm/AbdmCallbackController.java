package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.integration.AbdmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * IDC-016: gateway callbacks under /abdm/v3/callbacks/. Anonymous at the HTTP layer (PublicEndpointsSecurityConfig); the
 * gateway's signed bearer token is verified here against the gateway's key set and anything else is 401. Fails closed
 * when no key set is configured. The callbacks are acknowledged (202) only: acting on them belongs to the ABHA and
 * HIP/HIU stories, so nothing is stored or logged from the body.
 */
@RestController
class AbdmCallbackController {

    private static final Logger log = LoggerFactory.getLogger(AbdmCallbackController.class);

    private final JwtDecoder decoder;

    @Autowired
    AbdmCallbackController(AbdmProperties props) {
        this(props.jwksUri().isBlank() ? null : NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).build());
    }

    AbdmCallbackController(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @PostMapping("/abdm/v3/callbacks/**")
    ResponseEntity<Void> callback(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (decoder == null || authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw unauthorized();
        }
        try {
            decoder.decode(authorization.substring(7).strip());
        } catch (JwtException e) {
            log.warn("abdm callback refused: token not valid");
            throw unauthorized();
        }
        return ResponseEntity.accepted().build();
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Callback signature not valid.");
    }
}
