package com.nabd.hms.patient.securelink;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.ConsentGateSql;
import com.nabd.hms.shared.FileStore;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IDC-015: OTP-protected links to a document. The token (tenant id + 256 random bits) is shown once and only its SHA-256 is stored.
 * The public calls return an outcome instead of throwing, because a refused attempt (wrong code, locked) must still commit its
 * counters and its secure_link_opens row. Limits (MF-24): OTP sends at most 1 per 60 s and 5 per hour per link and 10 per hour per
 * recipient; 5 wrong codes lock the link for 30 minutes.
 * shortcut: the OTP hash is salted with the link id only (a 6-digit code is brute-forceable offline from a stolen row); the 10-minute
 * expiry and the 5-attempt lock are the control. Move to a keyed HMAC with the tenant data key when a secret store is wired.
 */
@Service
public class SecureLinkService {

    private static final Logger log = LoggerFactory.getLogger(SecureLinkService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration LOCK = Duration.ofMinutes(30);
    static final Duration FILE_URL_TTL = Duration.ofSeconds(60);
    static final int MAX_FAILED = 5;
    static final int SENDS_PER_LINK_HOUR = 5;
    static final int SENDS_PER_RECIPIENT_HOUR = 10;

    public record IssueRequest(String documentType, UUID documentId, UUID recipientPatientId, Integer ttlDays, Boolean sendMessage, String language) {
    }

    public record Issued(UUID linkId, Instant expiresAt) {
    }

    public enum Refusal { EXPIRED, LOCKED, RATE_LIMITED, OTP_INVALID }

    public record Opened(String fileUrl, String mime, String title) {
    }

    public record Result<T>(T value, Refusal refusal) {
        static <T> Result<T> ok(T v) {
            return new Result<>(v, null);
        }

        static <T> Result<T> no(Refusal r) {
            return new Result<>(null, r);
        }
    }

    private record Link(UUID id, String documentType, UUID documentId, UUID patientId, UUID recipientId, Instant expiresAt, int maxOpens, int openCount,
                        int failedOtps, Instant lockedUntil, String otpHash, Instant otpExpiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final FileStore files;
    private final NotificationPort notifications;
    private final SecureLinkOtpDelivery otpSender;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final String baseUrl;

    SecureLinkService(JdbcTemplate jdbc, TenantContext tenantContext, Clock clock, FileStore files, NotificationPort notifications,
                      SecureLinkOtpDelivery otpSender, AuditService audit, ActorDirectory actors,
                      @Value("${app.secure-links.base-url:http://localhost:3810}") String baseUrl) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.files = files;
        this.notifications = notifications;
        this.otpSender = otpSender;
        this.audit = audit;
        this.actors = actors;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // ---------------------------------------------------------------- staff: issue

    @Transactional
    public Issued issue(UUID staffId, String ip, IssueRequest req) {
        UUID tenant = tenantContext.current();
        if (!List.of("report", "document").contains(req.documentType())) {
            // rx and invoice PDFs have no stored file to link to yet (no Rx PDF renderer; invoice PDFs are not in the file store)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "document-type-unavailable", "Document type not available",
                    "Only report and document links can be issued for now.");
        }
        FileStore.FileInfo f = files.info(req.documentId()).filter(i -> "available".equals(i.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The document was not found."));
        UUID patient = f.patientId();
        if (patient == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The document is not attached to a patient.");
        }
        if (!processable(patient)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The document was not found.");
        }
        String firstName = recipient(req.recipientPatientId()).orElseThrow(() -> notAllowed());
        if (!allowedRecipient(patient, req.recipientPatientId())) {
            throw notAllowed();
        }
        Integer tenantDays = jdbc.queryForObject("SELECT secure_link_ttl_days FROM tenants WHERE id = ?", Integer.class, tenant);
        int days = req.ttlDays() != null ? req.ttlDays() : tenantDays;
        if (days < 1 || days > 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "ttlDays must be between 1 and 30.");
        }
        byte[] tenantBytes = ByteBuffer.allocate(16).putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits()).array();
        byte[] rnd = new byte[32];
        RANDOM.nextBytes(rnd);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(48).put(tenantBytes).put(rnd).array());
        Instant expires = clock.instant().plus(Duration.ofDays(days));
        UUID id = jdbc.queryForObject("INSERT INTO secure_links (tenant_id, token_hash, document_type, document_id, patient_id, recipient_patient_id, expires_at, created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?) RETURNING id", UUID.class, tenant, sha256(token), req.documentType(), req.documentId(), patient, req.recipientPatientId(),
                java.sql.Timestamp.from(expires), staffId);
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Forbidden"));
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "secure_link.issued", "secure_link", id, null,
                Map.of("documentType", req.documentType(), "ttlDays", days));
        if (!Boolean.FALSE.equals(req.sendMessage())) { // the body carries the first name and the link only: no document name, no drug, no amount
            notifications.request(new NotificationRequest(NotificationPurpose.SECURE_LINK, "secure_link", id, req.recipientPatientId(), null,
                    req.language() == null ? "en" : req.language(), List.of(firstName, baseUrl + "/l/" + token), null, null, null, "secure-link:" + id));
        }
        return new Issued(id, expires);
    }

    // ---------------------------------------------------------------- public: otp, open

    /** Token to tenant: the first 16 bytes of the token. Empty for anything malformed. */
    public static Optional<UUID> tenantOf(String token) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token);
            if (raw.length != 48) {
                return Optional.empty();
            }
            ByteBuffer b = ByteBuffer.wrap(raw);
            return Optional.of(new UUID(b.getLong(), b.getLong()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result<Void> sendOtp(String token, String ip, String userAgent) {
        UUID tenant = tenantContext.current();
        Link l = lock(token);
        Instant now = clock.instant();
        if (l == null || gone(l, now)) {
            if (l != null) {
                logOpen(tenant, l.id(), ip, userAgent, "expired");
            }
            return Result.no(Refusal.EXPIRED);
        }
        if (l.lockedUntil() != null && l.lockedUntil().isAfter(now)) {
            return Result.no(Refusal.LOCKED);
        }
        Instant hourAgo = now.minus(Duration.ofHours(1));
        Integer perLink = jdbc.queryForObject("SELECT count(*) FROM secure_link_opens WHERE tenant_id = ? AND link_id = ? AND outcome = 'otp_sent' AND opened_at > ?",
                Integer.class, tenant, l.id(), java.sql.Timestamp.from(hourAgo));
        Integer perRecipient = jdbc.queryForObject("SELECT count(*) FROM secure_link_opens o JOIN secure_links s ON s.tenant_id = o.tenant_id AND s.id = o.link_id "
                        + "WHERE o.tenant_id = ? AND s.recipient_patient_id = ? AND o.outcome = 'otp_sent' AND o.opened_at > ?",
                Integer.class, tenant, l.recipientId(), java.sql.Timestamp.from(hourAgo));
        java.sql.Timestamp lastTs = jdbc.queryForObject("SELECT max(opened_at) FROM secure_link_opens WHERE tenant_id = ? AND link_id = ? AND outcome = 'otp_sent'",
                java.sql.Timestamp.class, tenant, l.id());
        Instant last = lastTs == null ? null : lastTs.toInstant();
        if (perLink >= SENDS_PER_LINK_HOUR || perRecipient >= SENDS_PER_RECIPIENT_HOUR || (last != null && last.plusSeconds(60).isAfter(now))) {
            return Result.no(Refusal.RATE_LIMITED);
        }
        String phone = jdbc.queryForObject("SELECT phone FROM patients WHERE tenant_id = ? AND id = ?", String.class, tenant, l.recipientId());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("UPDATE secure_links SET otp_hash = ?, otp_expires_at = ? WHERE id = ?", otpHash(l.id(), code), java.sql.Timestamp.from(now.plus(OTP_TTL)), l.id());
        logOpen(tenant, l.id(), ip, userAgent, "otp_sent");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                otpSender.send(phone, code, l.id()); // outside the transaction: a slow provider holds no connection
            }
        });
        return Result.ok(null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result<Opened> open(String token, String otp, String ip, String userAgent) {
        UUID tenant = tenantContext.current();
        Link l = lock(token);
        Instant now = clock.instant();
        if (l == null) {
            return Result.no(Refusal.EXPIRED);
        }
        if (gone(l, now) || !processable(l.patientId())) {
            logOpen(tenant, l.id(), ip, userAgent, "expired");
            return Result.no(Refusal.EXPIRED);
        }
        if (l.lockedUntil() != null && l.lockedUntil().isAfter(now)) {
            logOpen(tenant, l.id(), ip, userAgent, "locked");
            return Result.no(Refusal.LOCKED);
        }
        boolean ok = l.otpHash() != null && l.otpExpiresAt() != null && l.otpExpiresAt().isAfter(now)
                && MessageDigest.isEqual(l.otpHash().getBytes(StandardCharsets.US_ASCII), otpHash(l.id(), otp).getBytes(StandardCharsets.US_ASCII));
        if (!ok) {
            int failed = l.failedOtps() + 1;
            if (failed >= MAX_FAILED) {
                jdbc.update("UPDATE secure_links SET failed_otps = 0, otp_hash = NULL, otp_expires_at = NULL, locked_until = ? WHERE id = ?", java.sql.Timestamp.from(now.plus(LOCK)), l.id());
                logOpen(tenant, l.id(), ip, userAgent, "locked");
                return Result.no(Refusal.LOCKED);
            }
            jdbc.update("UPDATE secure_links SET failed_otps = ? WHERE id = ?", failed, l.id());
            logOpen(tenant, l.id(), ip, userAgent, "otp_failed");
            return Result.no(Refusal.OTP_INVALID);
        }
        Optional<FileStore.FileInfo> f = files.info(l.documentId()).filter(i -> "available".equals(i.status()) && l.patientId().equals(i.patientId()));
        if (f.isEmpty()) {
            logOpen(tenant, l.id(), ip, userAgent, "expired");
            return Result.no(Refusal.EXPIRED);
        }
        jdbc.update("UPDATE secure_links SET otp_hash = NULL, otp_expires_at = NULL, failed_otps = 0, open_count = open_count + 1 WHERE id = ?", l.id());
        logOpen(tenant, l.id(), ip, userAgent, "opened");
        audit.record(tenant, "system", null, "patient via secure link", "Patient", ip, "secure_link.opened", "secure_link", l.id(), null,
                Map.of("documentType", l.documentType()));
        FileStore.SignedUrl url = files.downloadUrl(l.documentId(), FILE_URL_TTL);
        return Result.ok(new Opened(url.url(), f.get().contentType(), "report".equals(l.documentType()) ? "Report" : "Document"));
    }

    // ---------------------------------------------------------------- helpers

    private Link lock(String token) {
        return jdbc.query("SELECT id, document_type, document_id, patient_id, recipient_patient_id, expires_at, max_opens, open_count, failed_otps, locked_until, otp_hash, otp_expires_at "
                        + "FROM secure_links WHERE token_hash = ? FOR UPDATE", (rs, i) -> new Link(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant(), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getString(11), rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()),
                sha256(token)).stream().findFirst().orElse(null);
    }

    private static boolean gone(Link l, Instant now) {
        return !l.expiresAt().isAfter(now) || l.openCount() >= l.maxOpens();
    }

    private void logOpen(UUID tenant, UUID linkId, String ip, String ua, String outcome) {
        jdbc.update("INSERT INTO secure_link_opens (tenant_id, link_id, opened_at, ip, user_agent, outcome) VALUES (?,?,?,?::inet,?,?)",
                tenant, linkId, java.sql.Timestamp.from(clock.instant()), ip, ua == null ? null : ua.substring(0, Math.min(ua.length(), 300)), outcome);
    }

    /** IDC-006 default-deny gate on the document's patient: a withdrawn or missing processing consent stops issue and open. */
    private boolean processable(UUID patient) {
        return !jdbc.queryForList("SELECT 1 FROM patients p WHERE p.id = ? AND p.tenant_id = app_tenant_id() AND p.status = 'active' "
                + ConsentGateSql.gate("p.id", "p.tenant_id"), patient).isEmpty();
    }

    private Optional<String> recipient(UUID id) {
        return jdbc.query("SELECT name FROM patients WHERE id = ? AND tenant_id = app_tenant_id() AND status = 'active' AND phone IS NOT NULL",
                (rs, i) -> rs.getString(1).trim().split("\\s+")[0], id).stream().findFirst();
    }

    /** The patient, their guardian (minor), or a chat-added caregiver whose proxy scope is GUARDIAN. BOOK_ONLY and NONE never get clinical links. */
    private boolean allowedRecipient(UUID patient, UUID recipient) {
        if (patient.equals(recipient)) {
            return true;
        }
        return !jdbc.queryForList("SELECT 1 FROM patients WHERE id = ? AND guardian_id = ? AND tenant_id = app_tenant_id() "
                + "UNION ALL SELECT 1 FROM chat_added_members WHERE tenant_id = app_tenant_id() AND member_patient_id = ? AND caregiver_patient_id = ? AND proxy_scope = 'GUARDIAN'",
                patient, recipient, patient, recipient).isEmpty();
    }

    private static ApiException notAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "recipient-not-allowed", "Recipient not allowed", "That recipient may not receive this document.");
    }

    static String otpHash(UUID linkId, String code) {
        return sha256(linkId + ":" + code);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
