package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-034: the committed api/openapi/core.yaml must equal what springdoc generates from the controllers (CI stage 3 runs this).
 * To refresh the file after an endpoint change: mvn test -Dtest=OpenApiSpecApiTest -Dopenapi.write=true, then commit it.
 */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.writer-with-order-by-keys=true"})
class OpenApiSpecApiTest extends ApiTestBase {

    static final Path COMMITTED = Path.of("../api/openapi/core.yaml");

    @Autowired OpenApiWebMvcResource resource;

    @Test
    @SuppressWarnings("unchecked")
    void generatedSpecEqualsCommittedAndEveryOperationIsComplete() throws Exception {
        var req = new MockHttpServletRequest("GET", "/v3/api-docs.yaml"); // springdoc strips the docs path from the request URL
        String generated = new String(resource.openapiYaml(req, "/v3/api-docs.yaml", Locale.ROOT), StandardCharsets.UTF_8);
        if (Boolean.getBoolean("openapi.write")) Files.writeString(COMMITTED, generated);
        assertEquals(Files.readString(COMMITTED), generated, "api/openapi/core.yaml is stale: regenerate with -Dopenapi.write=true");

        Map<String, Object> doc = new org.yaml.snakeyaml.Yaml().load(generated);
        Map<String, Map<String, Map<String, Object>>> paths = (Map<String, Map<String, Map<String, Object>>>) doc.get("paths");
        var ids = new java.util.HashSet<String>();
        int ops = 0;
        for (var path : paths.entrySet()) {
            for (var op : path.getValue().entrySet()) {
                ops++;
                String where = op.getKey() + " " + path.getKey();
                var o = op.getValue();
                assertTrue(ids.add((String) o.get("operationId")), "operationId missing or duplicate: " + where);
                assertTrue(!((java.util.List<?>) o.get("tags")).isEmpty(), "no tag: " + where);
                assertTrue(o.get("x-required-permission") instanceof String, "no x-required-permission: " + where);
                var responses = (Map<String, Map<String, Object>>) o.get("responses");
                assertTrue(responses.get("default").toString().contains("application/problem+json"), "no Problem+JSON default: " + where);
            }
        }
        assertTrue(ops >= 264, "expected 264+ operations, got " + ops);
    }
}
