package com.nabd.hms.config;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Public verification keys (no private material), cacheable 5 minutes. */
@RestController
class JwksController {

    private final Map<String, Object> body;

    JwksController(SigningKeyProvider keys) {
        this.body = new JWKSet(new java.util.ArrayList<com.nimbusds.jose.jwk.JWK>(keys.keys())).toPublicJWKSet().toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(body);
    }
}
