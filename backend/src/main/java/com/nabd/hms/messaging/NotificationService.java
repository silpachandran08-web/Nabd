package com.nabd.hms.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OutboxRetryLaterException;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.nabd.hms.shared.notify.CancelReason;
import com.nabd.hms.shared.notify.ConsentClass;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-004: the notification contract's implementation and its in-process consumer (S4; in S5 the Comms Gateway takes the
 * consumer side and calls {@link #claim} over HTTP). Render params live encrypted in notification_requests; events carry ids.
 * request() and cancelBySubject() run in the caller's transaction.
 */
@Service
public class NotificationService implements NotificationPort {

    private static final String NOT_FOUND = "No such notification request.";

    static final String COLUMN = "params";
    private static final long MAX_DEFER_SECONDS = 3600;

    private final NotificationRequestRepository repo;
    private final WhatsAppTemplateRepository templates;
    private final WhatsAppMessageService whatsApp;
    private final EventPublisher publisher;
    private final TenantContext tenantContext;
    private final FieldCipher cipher;
    private final ObjectMapper json;
    private final ClinicClock clock;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final CommsCutover cutover;
    private final com.nabd.hms.organization.api.QuietHours quietHours;

    NotificationService(NotificationRequestRepository repo, WhatsAppTemplateRepository templates, WhatsAppMessageService whatsApp,
                        EventPublisher publisher, TenantContext tenantContext, FieldCipher cipher, ObjectMapper json, ClinicClock clock,
                        TenantScope tenantScope, PlatformTransactionManager txManager, CommsCutover cutover,
                        com.nabd.hms.organization.api.QuietHours quietHours) {
        this.quietHours = quietHours;
        this.cutover = cutover;
        this.tenantScope = tenantScope;
        this.tx = new TransactionTemplate(txManager);
        this.repo = repo;
        this.templates = templates;
        this.whatsApp = whatsApp;
        this.publisher = publisher;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public NotificationOutcome request(NotificationRequest r) {
        UUID tenantId = tenantContext.current();
        NotificationPurpose purpose = r.purpose();
        String language = r.language() == null ? "en" : r.language();
        var template = templates.findByName(tenantId, purpose.templateKey(), language)
                .filter(WhatsAppTemplateRepository.Template::sendable)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "template-not-sendable",
                        "Notification template not available",
                        "No approved template for " + purpose.code() + " in " + language + ".", Map.of("code", "TEMPLATE_NOT_SENDABLE")));
        TemplateRules.checkParams(r.params(), template.paramCount());

        boolean consented = purpose.consentClass() == ConsentClass.NONE
                || repo.hasConsent(tenantId, r.recipientPatientId(), purpose.consentClass().consentType());
        String status = consented ? "requested" : "blocked";
        UUID id = UUID.randomUUID();
        var priority = r.priority() == null ? purpose.defaultPriority() : r.priority();
        Instant notBefore = QuietHoursRule.releaseAt(quietHours.window(tenantId).orElse(null), clock.zone(tenantId), clock.now(), r.notBefore(), priority); // ADM-028
        boolean inserted = repo.insert(id, tenantId, purpose.code(), r.subjectType(), r.subjectId(), r.recipientPatientId(),
                seal(tenantId, id, r), language, priority.name(), purpose.channelPolicy().name(), notBefore, r.notAfter(),
                r.dedupeKey(), status);
        if (!inserted) {
            var existing = repo.byDedupeKey(tenantId, r.dedupeKey());
            return new NotificationOutcome(existing.id(), existing.status(), true);
        }
        if (consented) {
            var data = new LinkedHashMap<String, Object>();
            data.put("requestId", id);
            data.put("purpose", purpose.code());
            data.put("priority", priority.name());
            data.put("channelPolicy", purpose.channelPolicy().name());
            if (notBefore != null) data.put("notBefore", notBefore);
            if (r.notAfter() != null) data.put("notAfter", r.notAfter());
            publisher.publish(tenantId, DomainEventType.NOTIFICATION_REQUESTED, id, data);
        }
        return new NotificationOutcome(id, status, false);
    }

    @Override
    @Transactional
    public int cancelBySubject(String subjectType, UUID subjectId, CancelReason reason) {
        UUID tenantId = tenantContext.current();
        List<UUID> ids = repo.cancelBySubject(tenantId, subjectType, subjectId);
        ids.forEach(id -> cancelled(tenantId, id, reason));
        return ids.size();
    }

    /** Claim check for the Comms side: render params and recipient of a pending request. 410 SUBJECT_GONE once cancelled. */
    public NotificationClaim claim(UUID id) {
        UUID tenantId = tx.execute(s -> repo.tenantOf(id)).orElseThrow(() -> ApiException.notFound(NOT_FOUND)); // the caller knows only the id
        return tenantScope.run(tenantId, () -> {
            var row = repo.find(tenantId, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
            return render(row, () -> repo.patientPhone(tenantId, row.recipientPatientId()));
        });
    }

    /** The in-process consumer: hands one pending request to the WhatsApp sender. Idempotent on the row's status. */
    @Transactional
    public void deliver(UUID id) {
        UUID tenantId = tenantContext.current();
        var row = repo.find(tenantId, id).orElse(null);
        if (row == null || !"requested".equals(row.status())) {
            return; // redelivery, or cancelled/blocked meanwhile
        }
        if (cutover.remote(tenantId)) {
            return; // COM-008: the Comms Gateway owns this tenant's sends; the row stays 'requested' for its claim
        }
        Instant now = clock.now();
        if (row.notAfter() != null && now.isAfter(row.notAfter())) {
            repo.move(tenantId, id, "cancelled"); // window closed: sending late is worse than not sending
            return;
        }
        if (row.notBefore() != null && now.isBefore(row.notBefore())) {
            throw new OutboxRetryLaterException("notification " + id + " is not due yet",
                    Math.min(MAX_DEFER_SECONDS, Math.max(1, Duration.between(now, row.notBefore()).toSeconds())));
        }
        var purpose = NotificationPurpose.ofCode(row.purpose());
        if (purpose.consentClass() != ConsentClass.NONE && row.recipientPatientId() != null
                && !repo.hasConsent(tenantId, row.recipientPatientId(), purpose.consentClass().consentType())) {
            repo.move(tenantId, id, "cancelled"); // withdrawn between request and send
            cancelled(tenantId, id, CancelReason.CONSENT_WITHDRAWN);
            return;
        }
        NotificationClaim c = render(row, () -> repo.patientPhone(tenantId, row.recipientPatientId()));
        whatsApp.queueTemplate(c.recipient().patientId(), c.recipient().phoneE164(), c.templateKey(), c.language(), c.params());
        repo.move(tenantId, id, "dispatched");
    }

    private NotificationClaim render(NotificationRequestRepository.Row row, java.util.function.Supplier<java.util.Optional<String>> patientPhone) {
        if ("cancelled".equals(row.status()) || "blocked".equals(row.status()) || row.paramsEnc() == null) {
            throw new ApiException(HttpStatus.GONE, "subject-gone", "Notification no longer applies",
                    "The request was cancelled, blocked or its render data has been purged.", Map.of("code", "SUBJECT_GONE"));
        }
        JsonNode body = json.readTree(cipher.decrypt(row.paramsEnc(), aad(row.tenantId(), row.id())));
        List<String> params = new java.util.ArrayList<>();
        body.path("p").forEach(n -> params.add(n.asString()));
        String phone = body.hasNonNull("to") ? body.path("to").asString()
                : patientPhone.get().orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        var purpose = NotificationPurpose.ofCode(row.purpose());
        return new NotificationClaim(row.id(), row.tenantId(), purpose.code(), purpose.templateKey(), row.language(), params,
                new NotificationClaim.Recipient(phone, row.recipientPatientId()), purpose.consentClass().name().toLowerCase(),
                row.notBefore(), row.notAfter());
    }

    private void cancelled(UUID tenantId, UUID id, CancelReason reason) {
        publisher.publish(tenantId, DomainEventType.NOTIFICATION_CANCELLED, id, Map.of("requestId", id, "reason", reason.wire()));
    }

    private byte[] seal(UUID tenantId, UUID id, NotificationRequest r) {
        var body = new LinkedHashMap<String, Object>();
        body.put("p", r.params());
        if (r.recipientPhone() != null) body.put("to", r.recipientPhone());
        return cipher.encrypt(json.writeValueAsBytes(body), aad(tenantId, id));
    }

    private static FieldCipher.Aad aad(UUID tenantId, UUID id) {
        return FieldCipher.Aad.of(tenantId, "notification_requests", COLUMN, id);
    }

}
