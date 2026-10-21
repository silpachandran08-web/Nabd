package com.nabd.hms.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-043 negative control: the fuzzer must flag a deliberately broken endpoint and stay quiet on a correct one. */
class IdReplayFuzzerNegativeControlTest {

    private static final String B_PATIENT = UUID.randomUUID().toString();

    private static Map<String, Object> spec() {
        Map<String, Object> idParam = Map.of("name", "patientId", "in", "path", "required", true, "schema", Map.of("type", "string", "format", "uuid"));
        Map<String, Object> ok = Map.of("responses", Map.of("200", Map.of("description", "ok")));
        return Map.of("paths", Map.of(
                "/v1/patients/{patientId}", Map.of("get", Map.of("parameters", List.of(idParam)), "delete", Map.of("parameters", List.of(idParam))),
                "/v1/patients", Map.of("get", ok)));
    }

    private static HttpServer server(boolean broken) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/patients", ex -> {
            String p = ex.getRequestURI().getPath();
            boolean one = p.length() > "/v1/patients/".length();
            // the broken variant skips the tenant check: it answers for any id it is given
            int code = one && !broken ? 404 : 200;
            byte[] body = (one ? "{\"id\":\"" + p.substring("/v1/patients/".length()) + "\"}" : "{\"data\":[]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        return s;
    }

    private static List<IdReplayFuzzer.Finding> run(boolean broken) throws Exception {
        HttpServer s = server(broken);
        try {
            var f = new IdReplayFuzzer(spec(), "http://127.0.0.1:" + s.getAddress().getPort(), "tokenA", Map.of("patients", List.of(B_PATIENT)));
            return f.run();
        } finally {
            s.stop(0);
        }
    }

    @Test
    void brokenEndpointIsCaught() throws Exception {
        var findings = run(true);
        assertThat(findings).isNotEmpty();
        assertThat(findings).extracting(IdReplayFuzzer.Finding::rule).contains("B_ID_IN_2XX_BODY");
        assertThat(findings).extracting(IdReplayFuzzer.Finding::method).contains("GET", "DELETE");
    }

    @Test
    void correctEndpointPasses() throws Exception {
        assertThat(run(false)).isEmpty();
    }
}
