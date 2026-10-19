package com.nabd.hms.platform.provisioning;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S11) for OPS-012 AC4: provisioning step 'send owner invite' against an SMTP server that accepts
 * the connection and then never answers (a timeout, not a refused port). The step must record the failure in its
 * own short transaction and the job must be retryable.
 */
class S11QaProvisioningSmtpTimeoutTest extends ApiTestBase {

    private static final AtomicInteger CONNECTIONS = new AtomicInteger();
    private static final List<Socket> HELD = new CopyOnWriteArrayList<>();
    private static final ServerSocket SILENT_SMTP = start();

    private static ServerSocket start() {
        try {
            ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        HELD.add(s.accept()); // never sends the 220 greeting
                        CONNECTIONS.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "qa4-silent-smtp");
            t.setDaemon(true);
            t.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() throws IOException {
        for (Socket s : HELD) s.close();
        SILENT_SMTP.close();
    }

    @DynamicPropertySource
    static void silentSmtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> String.valueOf(SILENT_SMTP.getLocalPort()));
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("spring.mail.username", () -> "qa4-sender@nabd.invalid"); // the From address; a blank one fails before connecting
    }

    private record Run(UUID jobId, String token, Map<String, Object> inviteStep, long inviteStepMs) { }

    @SuppressWarnings("unchecked")
    private Run runToInvite(String tag) {
        SeededOperator op = seedOperator("q4smtp-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", "q4smtp-" + tag, "tenantName", "QA Smtp Clinic", "region", "IN",
                "ownerEmail", "owner-" + tag + "@nabd.health", "ownerName", "QA Owner", "ownerMobile", "+9198765" + (10000 + Math.abs(tag.hashCode() % 89999)),
                "brandName", "QA Smtp Brand " + tag, "path", "self_serve")), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID jobId = UUID.fromString((String) created.getBody().get("id"));
        Map<String, Object> body = null;
        long ms = 0;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            body = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
            ms = (System.nanoTime() - t0) / 1_000_000;
        }
        Map<String, Object> invite = ((List<Map<String, Object>>) body.get("steps")).stream()
                .filter(s -> "verify_invite_owner".equals(s.get("stepName"))).findFirst().orElseThrow();
        return new Run(jobId, token, invite, ms);
    }

    @Test
    void smtpTimeoutIsRecordedOnTheStepWithoutHangingTheRequest() {
        int before = CONNECTIONS.get();
        Run r = runToInvite(UUID.randomUUID().toString().substring(0, 8));

        assertThat(CONNECTIONS.get()).as("the invite email reached the (silent) SMTP server; step=" + r.inviteStep()).isGreaterThan(before);
        assertThat(r.inviteStepMs()).as("bounded by the 5 s SMTP read timeout, not the OS TCP timeout").isLessThan(15_000);
        assertThat((String) r.inviteStep().get("errorDetail")).contains("email not sent").doesNotContain("NoTransactionAllowed");
        // the step's own work (owner staff row) committed although SMTP failed
        assertThat(r.inviteStep().get("status")).isNotEqualTo("rolled_back");
    }

    // "and the job is retryable": after the timeout, asking the job to go again must re-attempt the invite email
    @Test
    @Tag("known-defect-OPS-012")
    void afterAnSmtpTimeoutTheInviteCanBeRetried() {
        Run r = runToInvite(UUID.randomUUID().toString().substring(0, 8));
        int afterFirst = CONNECTIONS.get();
        exchange("/v1/platform/provisioning-jobs/" + r.jobId() + "/advance", HttpMethod.POST, authed(r.token()), Map.class);
        assertThat(CONNECTIONS.get()).as("a retry re-sends the owner invite").isGreaterThan(afterFirst);
    }
}
