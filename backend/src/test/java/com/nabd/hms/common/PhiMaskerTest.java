package com.nabd.hms.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhiMaskerTest {

    @Test
    void masksPhonesPerMarket() {
        assertThat(PhiMasker.mask("to +919876543210 now")).isEqualTo("to +91******3210 now");   // IN
        assertThat(PhiMasker.mask("+971501234567")).isEqualTo("+97******4567");                 // AE
        assertThat(PhiMasker.mask("+966512345678")).isEqualTo("+96******5678");                 // SA
        assertThat(PhiMasker.mask("+97433123456")).isEqualTo("+97*****3456");                    // QA
        assertThat(PhiMasker.mask("call 9876543210")).isEqualTo("call ******3210");             // IN local
        assertThat(PhiMasker.mask("call 0501234567")).isEqualTo("call ******4567");             // AE/SA local
    }

    @Test
    void masksEmailNationalIdAndSixDigitCode() {
        assertThat(PhiMasker.mask("mail anas@example.com sent")).isEqualTo("mail a***@*** sent");
        assertThat(PhiMasker.mask("aadhaar 1234 5678 9012")).isEqualTo("aadhaar ************");
        assertThat(PhiMasker.mask("eid 784-1990-1234567-1")).isEqualTo("eid ************");
        assertThat(PhiMasker.mask("code 482913 end")).isEqualTo("code ****** end");
    }

    @Test
    void leavesIdsAndNormalTextAlone() {
        String s = "queue entry 3f2b8c1e-1234-4abc-9def-0123456789ab moved waiting -> called, 12 rows, 2026-10-18T10:00:00Z";
        assertThat(PhiMasker.mask(s)).isEqualTo(s);
        assertThat(PhiMasker.mask(null)).isNull();
    }

    @Test
    void prodBootFailsWhenOtpLoggingIsOn() {
        MockEnvironment prod = new MockEnvironment().withProperty("app.whatsapp.log-otp", "true");
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new OtpLoggingGuard(prod)).isInstanceOf(IllegalStateException.class);
        prod.setProperty("app.whatsapp.log-otp", "false");
        new OtpLoggingGuard(prod);
        MockEnvironment dev = new MockEnvironment().withProperty("app.whatsapp.log-otp", "true");
        new OtpLoggingGuard(dev);
    }

    @Test
    void defaultConfigHasOtpLoggingOff() throws Exception {
        var props = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        props.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        assertThat(props.getObject().getProperty("app.whatsapp.log-otp")).isEqualTo("${WHATSAPP_LOG_OTP:false}");
    }

    @Test
    void jsonEncoderEmitsTraceIdAndMasksMessage() throws Exception {
        var ev = new ch.qos.logback.classic.spi.LoggingEvent("x", (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("t"),
                ch.qos.logback.classic.Level.INFO, "sent to {} a@b.com", null, new Object[]{"+919876543210"});
        ev.setMDCPropertyMap(java.util.Map.of(RequestCorrelationFilter.MDC_KEY, "trace-1"));
        var json = new tools.jackson.databind.ObjectMapper().readTree(new PhiJsonEncoder().encode(ev));
        assertThat(json.get("traceId").asString()).isEqualTo("trace-1");
        assertThat(json.get("message").asString()).isEqualTo("sent to +91******3210 a***@***");
    }
}
