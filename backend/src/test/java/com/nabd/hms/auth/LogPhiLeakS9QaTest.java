package com.nabd.hms.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.PhiJsonEncoder;
import com.nabd.hms.common.PhiMaskingConverter;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9, PLT-037): login OTP verify, PIN reset confirm, break-glass, masking, JSON, prod guard. */
@Import(LogPhiLeakS9QaTest.Spy.class)
class LogPhiLeakS9QaTest extends ApiTestBase {

    static final List<String> SECRETS = new CopyOnWriteArrayList<>();
    @org.springframework.beans.factory.annotation.Autowired
    private com.nabd.hms.common.OutboxDispatcher dispatcher; // OPS-012: OTPs are sent by the outbox, after the request
    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attach() {
        SECRETS.clear();
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
    void fullOtpVerifyPinResetConfirmAndBreakGlassLeaveNoSecretsInRawOrMaskedLogs() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedBreakGlassDoctorRole(t.id()), "qa37@a.com", "+919812340099", false);
        String reason = "Zebra-reason collapsed in corridor";
        String access = loginAndGetAccessToken(s); // before the PIN is changed below
        http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919812340099")), Void.class);
        awaitSecret(); // PLT-006: the OTP is sent by the outbox handler, not on the request path
        String otp = SECRETS.get(0);
        ResponseEntity<Map> v = http.exchange(url("/v1/auth/otp/verify"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919812340099", "code", otp)), Map.class);
        // a wrong code too: failure path logging
        http.exchange(url("/v1/auth/otp/verify"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919812340099", "code", "000000")), Map.class);
        http.exchange(url("/v1/auth/pin/reset-request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919812340099")), Void.class);
        dispatcher.poll();
        String token = SECRETS.get(SECRETS.size() - 1);
        ResponseEntity<Void> rc = http.exchange(url("/v1/auth/pin/reset-confirm"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919812340099", "token", token, "newPin", "482913")), Void.class);
        assertThat(rc.getStatusCode().is2xxSuccessful() || rc.getStatusCode().is4xxClientError()).isTrue();
        assertThat(activateBreakGlass(access, reason, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(SECRETS).hasSizeGreaterThanOrEqualTo(2);
        assertThat(v.getStatusCode().value()).isIn(200, 202);
        PhiMaskingConverter conv = new PhiMaskingConverter();
        for (ILoggingEvent e : appender.list) {
            String raw = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            String masked = conv.convert(e) + new String(new PhiJsonEncoder().encode(e));
            for (String x : SECRETS) {
                assertThat(raw).as(raw).doesNotContain(x);
                assertThat(masked).doesNotContain(x);
            }
            assertThat(raw).as(raw).doesNotContain("9812340099").doesNotContain("Zebra-reason").doesNotContain("collapsed")
                    .doesNotContain("482913");
        }
    }

    @Test
    void maskingConverterAndJsonEncoderOnAccidentalLeaks() throws Exception {
        Logger lg = (Logger) LoggerFactory.getLogger("qa");
        LoggingEvent ev = new LoggingEvent("x", lg, Level.INFO, "p={} m={} c=123456", null,
                new Object[]{"+919876543210", "ab@x.org"});
        assertThat(new PhiMaskingConverter().convert(ev)).isEqualTo("p=+91******3210 m=a***@*** c=******");
        var json = new ObjectMapper().readTree(new PhiJsonEncoder().encode(ev));
        assertThat(json.get("message").asString()).doesNotContain("9876543210").doesNotContain("ab@x.org");
        assertThat(json.has("timestamp") && json.has("level") && json.has("traceId")).isTrue();
        // throwable text is masked too
        LoggingEvent err = new LoggingEvent("x", lg, Level.ERROR, "boom", new RuntimeException("phone +971501234567"), null);
        assertThat(new String(new PhiJsonEncoder().encode(err))).doesNotContain("501234567");
    }

    @Test
    void prodProfileWithLogOtpTrueFailsContextStartButDefaultAndFalseStart() {
        // use the real guard bean class by name (package-private)
        Class<?> guard;
        try { guard = Class.forName("com.nabd.hms.common.OtpLoggingGuard"); } catch (Exception ex) { throw new AssertionError(ex); }
        var r = new ApplicationContextRunner().withUserConfiguration(guard);
        r.withPropertyValues("spring.profiles.active=prod", "app.whatsapp.log-otp=true")
                .run(c -> assertThat(c).hasFailed());
        r.withPropertyValues("spring.profiles.active=prod").run(c -> assertThat(c).hasNotFailed());
        r.withPropertyValues("spring.profiles.active=prod", "app.whatsapp.log-otp=false").run(c -> assertThat(c).hasNotFailed());
        r.withPropertyValues("app.whatsapp.log-otp=true").run(c -> assertThat(c).hasNotFailed()); // non-prod may opt in
    }

    @TestConfiguration
    static class Spy {
        @Bean
        static BeanPostProcessor otpSpy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof WhatsAppOtpSender real) || bean.getClass().getName().contains("Spy")) return bean;
                    return (WhatsAppOtpSender) (phone, code) -> { SECRETS.add(code); real.send(phone, code); };
                }
            };
        }
    }


    private void awaitSecret() {
        for (int i = 0; i < 100 && SECRETS.isEmpty(); i++) {
            dispatcher.poll();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

}
