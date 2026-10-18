package com.nabd.hms.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw log events (before any masking) from the real sender and services must carry no OTP, reset token,
 * phone number or free-text reason. */
@Import(LogPhiLeakTest.Spy.class)
class LogPhiLeakTest extends ApiTestBase {

    static final List<String> SENT_SECRETS = new CopyOnWriteArrayList<>();

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attach() {
        SENT_SECRETS.clear();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
    }

    @Test
    void otpPinResetAndBreakGlassLogNoSecretsPhoneOrReasonText() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedBreakGlassDoctorRole(tenant.id()), "leak@a.com", "+919812340077", false);
        String reason = "Unconscious patient with chest pain in bay four";

        assertThat(http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "+919812340077")), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(http.exchange(url("/v1/auth/pin/reset-request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", tenant.slug(), "mobilePhone", "+919812340077")), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> bg = activateBreakGlass(loginAndGetAccessToken(staff), reason, null);
        assertThat(bg.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(SENT_SECRETS).hasSize(2); // the OTP and the reset token really went through the real sender
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent e : appender.list) {
            String line = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            for (String secret : SENT_SECRETS) {
                assertThat(line).doesNotContain(secret);
            }
            assertThat(line).doesNotContain("9812340077").doesNotContain("Unconscious").doesNotContain("chest pain");
        }
    }

    @TestConfiguration
    static class Spy {
        @Bean
        static BeanPostProcessor otpSpy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof WhatsAppOtpSender real) || bean.getClass().getName().contains("Spy")) {
                        return bean;
                    }
                    return (WhatsAppOtpSender) (phone, code) -> {
                        SENT_SECRETS.add(code);
                        real.send(phone, code);
                    };
                }
            };
        }
    }
}
