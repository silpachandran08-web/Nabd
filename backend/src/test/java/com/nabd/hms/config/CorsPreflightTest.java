package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.Reader;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-001 / H-02: every verb the API contract uses must pass the browser preflight from the staff origin only. */
class CorsPreflightTest extends ApiTestBase {

    private static final String STAFF_ORIGIN = "http://localhost:3000"; // first entry of app.cors.allowed-origins
    private static final String PREG = "/v1/clinical/patients/00000000-0000-0000-0000-000000000001/pregnancy-status";

    private ResponseEntity<String> preflight(String origin, String method, String path) {
        HttpHeaders h = new HttpHeaders();
        h.set("Origin", origin);
        h.set("Access-Control-Request-Method", method);
        h.set("Access-Control-Request-Headers", "authorization,content-type");
        return http.exchange(url(path), HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
    }

    @SuppressWarnings("unchecked")
    private Set<String> openapiVerbs() throws Exception {
        Set<String> verbs = new TreeSet<>();
        try (Reader r = new FileReader("../api/openapi.yaml")) {
            Map<String, Object> doc = new Yaml().load(r);
            for (Object item : ((Map<String, Object>) doc.get("paths")).values())
                for (String k : ((Map<String, Object>) item).keySet())
                    if (Set.of("get", "post", "put", "patch", "delete").contains(k)) verbs.add(k.toUpperCase());
        }
        return verbs;
    }

    @Test
    void everyVerbInTheOpenApiContractPassesPreflightFromTheStaffOrigin() throws Exception {
        Set<String> verbs = openapiVerbs();
        assertThat(verbs).contains("GET", "POST", "PUT", "PATCH", "DELETE");
        for (String verb : verbs) {
            ResponseEntity<String> r = preflight(STAFF_ORIGIN, verb, PREG);
            assertThat(r.getStatusCode()).as(verb).isEqualTo(HttpStatus.OK);
            assertThat(r.getHeaders().getFirst("Access-Control-Allow-Methods")).as(verb).contains(verb);
            assertThat(r.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(STAFF_ORIGIN);
            assertThat(r.getHeaders().getFirst("Access-Control-Max-Age")).isEqualTo("600");
        }
    }

    @Test
    void putPreflightForAllergyStatusPasses() {
        ResponseEntity<String> r = preflight(STAFF_ORIGIN, "PUT", "/v1/clinical/patients/00000000-0000-0000-0000-000000000001/allergies/status");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst("Access-Control-Allow-Methods")).contains("PUT");
    }

    @Test
    void foreignOriginIsRejectedWithoutWildcardWidening() {
        ResponseEntity<String> r = preflight("https://evil.example.com", "PUT", PREG);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }
}
