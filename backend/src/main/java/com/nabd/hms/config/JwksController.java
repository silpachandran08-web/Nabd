package com.nabd.hms.config;

import com.nabd.hms.shared.security.TokenPopulation;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Public verification keys (no private material), cacheable 5 minutes. PLT-009: one JWKS per population
 * (/.well-known/jwks/{staff|owner|platform}.json); the legacy /.well-known/jwks.json and ?population= serve the same sets
 * (staff by default). The internal population's keys belong to the calling services and are not published (404).
 */
@RestController
class JwksController {

    private final Map<TokenPopulation, Map<String, Object>> bodies = new EnumMap<>(TokenPopulation.class);

    JwksController(PopulationKeys keys) {
        for (TokenPopulation p : TokenPopulation.values()) {
            if (p != TokenPopulation.INTERNAL) bodies.put(p, keys.publicSet(p).toJSONObject());
        }
    }

    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<Map<String, Object>> jwks(@RequestParam(required = false) String population) {
        return serve(population == null ? "staff" : population);
    }

    @GetMapping("/.well-known/jwks/{population}.json")
    ResponseEntity<Map<String, Object>> jwksFor(@PathVariable String population) {
        return serve(population);
    }

    private ResponseEntity<Map<String, Object>> serve(String slug) {
        Map<String, Object> body = bodies.get(TokenPopulation.fromSlug(slug));
        if (body == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(body);
    }
}
