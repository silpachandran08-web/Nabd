package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.integration.AbdmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** IDC-018: the gateway-signature check of {@link AbdmCallbackController} for the /abdm/v3/hip/ endpoints: fails closed without a key set, anything not signed by the gateway is 401. */
@Component
class AbdmCallbackAuth {

    private static final Logger log = LoggerFactory.getLogger(AbdmCallbackAuth.class);

    private final JwtDecoder decoder;

    @Autowired
    AbdmCallbackAuth(AbdmProperties props) {
        this(props.jwksUri().isBlank() ? null : NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).build());
    }

    AbdmCallbackAuth(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    void require(String authorization) {
        if (decoder == null || authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw unauthorized();
        }
        try {
            decoder.decode(authorization.substring(7).strip());
        } catch (JwtException e) {
            log.warn("abdm share callback refused: token not valid");
            throw unauthorized();
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Callback signature not valid.");
    }
}
