package com.nabd.hms.common;

import ch.qos.logback.classic.Logger;
import com.nabd.hms.support.LogPhiCaptureExtension;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-028: masking in the real encoder output, JSON shape, and a negative control for the harness. */
class LogPhiHarnessTest {

    private static java.util.List<String[]> capture(Runnable log) {
        var cap = new LogPhiCaptureExtension.Capture();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        cap.setContext(root.getLoggerContext());
        cap.start();
        root.addAppender(cap);
        try {
            log.run();
        } finally {
            root.detachAppender(cap);
        }
        return cap.linesForTest();
    }

    @Test
    void phoneAndEmailAreMaskedInTheJsonLine() throws Exception {
        MDC.put("reasonCode", "PATIENT_LOOKUP_FAILED");
        var lines = capture(() -> LoggerFactory.getLogger("t.phone").info("lookup for +919812345678 and a.b@example.com, otp 481516"));
        MDC.remove("reasonCode");
        String line = lines.get(0)[1];
        assertThat(line).contains("+91******5678").doesNotContain("9812345678").doesNotContain("a.b@example.com").doesNotContain("481516");
        JsonNode j = new ObjectMapper().readTree(line);
        assertThat(j.propertyNames()).contains("timestamp", "level", "logger", "traceId", "tenantId", "staffId", "reasonCode");
        assertThat(j.get("reasonCode").asString()).isEqualTo("PATIENT_LOOKUP_FAILED");
        assertThat(LogPhiCaptureExtension.violations(lines)).isEmpty();
    }

    @Test
    void negativeControlHarnessNamesTheLoggerThatLeaksAMarker() {
        var lines = capture(() -> LoggerFactory.getLogger("t.leaky").info("patient SYNTH-PHI-NAME1 arrived"));
        assertThat(LogPhiCaptureExtension.violations(lines)).hasSize(1).first().asString().startsWith("t.leaky:");
    }

    @Test
    void maskerGoldenOtpAndIds() {
        assertThat(PhiMasker.mask("OTP is 4821 sent")).isEqualTo("OTP is ****** sent");
        assertThat(PhiMasker.mask("pin=12345678 ok")).isEqualTo("pin=****** ok");
        assertThat(PhiMasker.mask("qid 29850123456 x")).isEqualTo("qid ************ x");
        assertThat(PhiMasker.mask("encoded as " + new String("x".getBytes(StandardCharsets.UTF_8)))).isEqualTo("encoded as x");
    }
}
