package com.nabd.hms.auth;

import com.nabd.hms.common.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-006 (SYS-01): may an OTP go to this number now? At most 3 sends in the last 15 minutes and 10 in the last 24 h per
 * number, 60 s between sends, and a per-tenant hourly cap (plan value, else app.auth.otp.tenant-hourly-cap, 200).
 * Counted for every well-formed request whether or not the number belongs to anyone, so limits never reveal
 * existence. Hitting the tenant cap or a number's daily cap publishes iam.otp_abuse.detected.v1 once per window.
 * The phone is only ever an HMAC (pepper app.auth.otp.phone-hmac-key; required under the prod profile).
 * shortcut: the tenant cap is checked without a tenant-wide lock, so concurrent requests can overshoot it by a few; PLT-008 moves this to Valkey token buckets.
 */
@Component
public class OtpSendLimiter {

    private static final Logger log = LoggerFactory.getLogger(OtpSendLimiter.class);
    static final String ABUSE_EVENT = "iam.otp_abuse.detected.v1";
    static final int PER_WINDOW = 3;
    static final int PER_DAY = 10;
    static final Duration RESEND_GAP = Duration.ofSeconds(60);
    private static final long BUCKET_SECONDS = 60; // counter rows are per minute; the limits sum the buckets they cover
    private static final byte[] TENANT_ALERT_KEY = "tenant-cap-alert".getBytes(StandardCharsets.US_ASCII);

    enum Decision { SEND, LIMITED }

    private final OtpCounterRepository repo;
    private final EventPublisher events;
    private final int defaultTenantCap;
    private final byte[] pepper;
    private final java.time.Clock clock;

    OtpSendLimiter(OtpCounterRepository repo, EventPublisher events, Environment env, java.time.Clock clock) {
        this.clock = clock;
        this.repo = repo;
        this.events = events;
        this.defaultTenantCap = env.getProperty("app.auth.otp.tenant-hourly-cap", Integer.class, 200);
        String key = env.getProperty("app.auth.otp.phone-hmac-key", "");
        if (key.isBlank() && env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("app.auth.otp.phone-hmac-key (OTP_PHONE_HMAC_KEY) must be set in the prod profile (PLT-006)");
        }
        // non-prod without a key: a fixed local pepper, so dev and test runs need no secret
        this.pepper = key.isBlank() ? "nabd-local-otp-pepper".getBytes(StandardCharsets.US_ASCII) : Base64.getDecoder().decode(key);
    }

    byte[] phoneHmac(String e164Digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(e164Digits.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Runs inside the caller's tenant transaction. Records the send when it returns SEND. */
    Decision tryConsume(UUID tenantId, String e164Digits) {
        byte[] h = phoneHmac(e164Digits);
        Instant now = Instant.now(clock);
        Instant bucket = Instant.ofEpochSecond(now.getEpochSecond() / BUCKET_SECONDS * BUCKET_SECONDS);
        repo.lockWindow(tenantId, h, bucket);
        Map<String, Object> use = repo.numberUsage(tenantId, h, now.minus(Duration.ofMinutes(15)));
        Timestamp last = (Timestamp) use.get("last");
        if (last != null && last.toInstant().plus(RESEND_GAP).isAfter(now)) {
            return Decision.LIMITED;
        }
        if (((Number) use.get("in15")).intValue() >= PER_WINDOW) {
            return Decision.LIMITED;
        }
        int in24 = ((Number) use.get("in24")).intValue();
        if (in24 >= PER_DAY) {
            Instant day = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            if (repo.firstAlert(tenantId, phoneHmac("alert:" + e164Digits), day)) {
                alert(tenantId, day, in24, "number");
            }
            return Decision.LIMITED;
        }
        Instant hour = now.minus(Duration.ofHours(1));
        int tenantSends = repo.tenantSendsSince(tenantId, hour);
        Integer planCap = repo.planHourlyCap(tenantId);
        int cap = planCap != null ? planCap : defaultTenantCap;
        if (tenantSends >= cap) {
            if (repo.firstAlert(tenantId, TENANT_ALERT_KEY, now.truncatedTo(java.time.temporal.ChronoUnit.HOURS))) {
                alert(tenantId, hour, tenantSends, "tenant");
            }
            return Decision.LIMITED;
        }
        repo.recordSend(tenantId, h, bucket);
        return Decision.SEND;
    }

    private void alert(UUID tenantId, Instant windowStart, int sends, String capHit) {
        log.warn("OTP send cap hit (cap={}, tenant {}, sends {} since {})", capHit, tenantId, sends, windowStart);
        events.publish(tenantId, ABUSE_EVENT, Map.of("tenantId", tenantId.toString(), "windowStart", windowStart.toString(),
                "sendsInWindow", sends, "capHit", capHit));
    }

    @Scheduled(fixedDelayString = "${app.auth.otp.purge-interval-ms:3600000}", initialDelayString = "${app.auth.otp.purge-interval-ms:3600000}")
    @SchedulerLock(name = "otp-counter-purge", lockAtMostFor = "PT30M")
    public void purge() {
        int n = repo.purgeOlderThan48h();
        if (n > 0) {
            log.info("purged {} otp_send_counters rows older than 48 h", n);
        }
    }
}
