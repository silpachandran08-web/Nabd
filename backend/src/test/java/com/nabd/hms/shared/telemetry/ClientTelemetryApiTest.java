package com.nabd.hms.shared.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientTelemetryApiTest extends ApiTestBase {

    private String token() {
        SeededTenant t = seedTenant();
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "tel@a.com", "+919700000771", false));
    }

    @Test
    void acceptsScrubbedReportAndKeepsNoQueryStringOrMessage() {
        String tok = token();
        Logger lg = (Logger) LoggerFactory.getLogger(ClientTelemetryController.class);
        ListAppender<ILoggingEvent> ap = new ListAppender<>();
        ap.start();
        lg.addAppender(ap);
        try {
            var r = exchange("/v1/client-telemetry", HttpMethod.POST, authedJsonBody(tok, Map.of("kind", "error", "metric", "TypeError",
                    "route", "/patients/abc?name=Asha+Rao&phone=9812345678#top", "message", "Asha Rao failed", "stackHash", "ab12cd")), Void.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        } finally {
            lg.detachAppender(ap);
        }
        String line = ap.list.get(0).getFormattedMessage();
        assertThat(line).contains("route=/patients/abc ").doesNotContain("Asha").doesNotContain("9812345678");
    }

    @Test
    void boundaryReportCarriesReleaseAndRefusesAnUnsafeOne() {
        String tok = token();
        Logger lg = (Logger) LoggerFactory.getLogger(ClientTelemetryController.class);
        ListAppender<ILoggingEvent> ap = new ListAppender<>();
        ap.start();
        lg.addAppender(ap);
        try {
            var r = exchange("/v1/client-telemetry", HttpMethod.POST, authedJsonBody(tok, Map.of("kind", "error", "metric", "boundary.clinic.TypeError",
                    "route", "/consult", "stackHash", "ab12cd", "release", "a1b2c3d4e5f6")), Void.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        } finally {
            lg.detachAppender(ap);
        }
        assertThat(ap.list.get(0).getFormattedMessage()).contains("release=a1b2c3d4e5f6");
        assertThat(exchange("/v1/client-telemetry", HttpMethod.POST, authedJsonBody(tok, Map.of("kind", "error", "metric", "E", "route", "/",
                "release", "Asha Rao 9812345678")), Void.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesBadKindOversizeAndAnonymous() {
        String tok = token();
        assertThat(exchange("/v1/client-telemetry", HttpMethod.POST, authedJsonBody(tok, Map.of("kind", "x", "metric", "LCP", "route", "/")), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/client-telemetry", HttpMethod.POST, authedJsonBody(tok, Map.of("kind", "error", "metric", "E", "route", "/",
                "message", "x".repeat(500), "stackHash", "a".repeat(64), "pad", "y".repeat(5000))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(exchange("/v1/client-telemetry", HttpMethod.POST, jsonBody(Map.of("kind", "vital", "metric", "LCP", "route", "/")), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
