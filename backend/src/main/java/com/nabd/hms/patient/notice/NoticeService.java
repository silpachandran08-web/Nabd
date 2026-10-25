package com.nabd.hms.patient.notice;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.PrivacyNotices;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IDC-003 (ADR 0018 sections 4 and 5): a clinic's privacy notices. A published version is immutable; editing means a new
 * version whose locked (mandatory) clauses are still in it verbatim and in order. The clinic edits only the text around
 * them and its grievance contact. No legal wording lives in this class: the clauses come from notice_templates.
 */
@Service
@Transactional
class NoticeService implements PrivacyNotices {

    record Reack(boolean needsReAcknowledgement, String acceptedVersion, String currentVersion, String language) {
    }

    private final NoticeRepository repo;
    private final TenantContext tenantContext;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;

    NoticeService(NoticeRepository repo, TenantContext tenantContext, EventPublisher events, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
    }

    static String hash(List<String> sections) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", sections).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every locked clause must still be in the body, verbatim, in the template's order. */
    static boolean lockedClausesIntact(String body, List<String> sections) {
        int from = 0;
        for (String s : sections) {
            int at = body.indexOf(s, from);
            if (at < 0) {
                return false;
            }
            from = at + s.length();
        }
        return true;
    }

    @Override
    public void seedInitial() {
        UUID tenant = tenantContext.current();
        if (repo.hasAnyNotice()) {
            return;
        }
        NoticeRepository.Clinic c = repo.clinic(tenant);
        List<NoticeRepository.Template> templates = repo.latestTemplates(c.region());
        if (templates.isEmpty()) {
            return;
        }
        String contact = grievanceBlock(c);
        for (NoticeRepository.Template t : templates) {
            String body = t.bodyMd().replace("{{clinic_name}}", c.name()).replace("{{grievance_contact}}", contact);
            repo.insert(tenant, "1", t.language(), t.key(), body, hash(t.sections()), null);
        }
        events.publish(tenant, DomainEventType.PRIVACY_NOTICE_PUBLISHED, tenant,
                Map.of("noticeVersion", "1", "languages", templates.stream().map(NoticeRepository.Template::language).toList()));
        audit.record(tenant, "system", null, "Setup", "System", null, "organization.privacy_notice_seeded", "privacy_notice", tenant, null,
                Map.of("version", "1", "languages", templates.size()));
    }

    private static String grievanceBlock(NoticeRepository.Clinic c) {
        StringBuilder b = new StringBuilder("Grievance contact: ").append(nz(c.contactName()));
        if (!nz(c.contactEmail()).isEmpty()) b.append(", ").append(c.contactEmail());
        if (!nz(c.contactPhone()).isEmpty()) b.append(", ").append(c.contactPhone());
        return b.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Transactional(readOnly = true)
    List<NoticeRepository.Notice> versions() {
        return repo.all();
    }

    /** @return the new version */
    String publish(UUID staffId, String ip, String language, String bodyMd, String basedOnTemplate, String baseVersion) {
        UUID tenant = tenantContext.current();
        NoticeRepository.Template t = repo.template(basedOnTemplate, language)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-template", "Unknown template",
                        "No notice template with this key exists for this language."));
        if (!lockedClausesIntact(bodyMd, t.sections())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "mandatory-clause-locked", "Mandatory clause locked",
                    "A mandatory clause was removed, reworded or reordered. Only text around the locked clauses can change.");
        }
        Optional<Integer> current = repo.currentNumericVersion(language);
        if (baseVersion != null && !baseVersion.equals(current.map(String::valueOf).orElse(null))) {
            throw new ApiException(HttpStatus.CONFLICT, "version-conflict", "Version conflict",
                    "The notice changed since you opened it; reload and apply your edit again.");
        }
        String before = repo.current(language).map(n -> sha(n.bodyMd())).orElse(null);
        String next = String.valueOf(current.orElse(0) + 1);
        String h = hash(t.sections());
        try {
            repo.insert(tenant, next, language, t.key(), bodyMd, h, staffId);
        } catch (DuplicateKeyException e) { // two publishers raced for the same number
            throw new ApiException(HttpStatus.CONFLICT, "version-conflict", "Version conflict", "Another version was published at the same time.");
        }
        events.publish(tenant, DomainEventType.PRIVACY_NOTICE_PUBLISHED, tenant, Map.of("noticeVersion", next, "languages", List.of(language)));
        ActorDirectory.Actor a = actors.findActor(tenant, staffId).orElse(null);
        audit.record(tenant, "staff", staffId, a == null ? "Staff" : a.name(), a == null ? "Staff" : a.role(), ip, "organization.privacy_notice_published",
                "privacy_notice", tenant, before == null ? null : Map.of("version", String.valueOf(current.orElse(0)), "bodySha256", before),
                Map.of("version", next, "language", language, "mandatoryHash", h, "bodySha256", sha(bodyMd)));
        return next;
    }

    private static String sha(String s) {
        return hash(List.of(s));
    }

    /** The patient's notice state: only a granted consent that names a notice can be out of date; no row and legacy rows are IDC-004's. */
    @Transactional(readOnly = true)
    Reack reack(UUID patientId) {
        if (!repo.patientExists(patientId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Patient not found", "Patient not found");
        }
        Optional<NoticeRepository.Accepted> c = repo.latestConsent(patientId).filter(x -> x.granted() && x.version() != null);
        if (c.isEmpty()) {
            return new Reack(false, null, null, null);
        }
        NoticeRepository.Accepted a = c.get();
        Optional<NoticeRepository.Notice> cur = repo.current(a.language());
        boolean stale = cur.isPresent() && !cur.get().version().equals(a.version());
        return new Reack(stale, a.version(), cur.map(NoticeRepository.Notice::version).orElse(a.version()), a.language());
    }

    /** One tap at the desk: records the current notice as accepted (a new consent row; nothing is rewritten). */
    Reack acknowledge(UUID patientId, UUID staffId, String ip) {
        Reack r = reack(patientId);
        if (!r.needsReAcknowledgement()) {
            return r;
        }
        UUID tenant = tenantContext.current();
        repo.insertAck(tenant, patientId, r.currentVersion(), r.language(), staffId);
        events.publish(tenant, DomainEventType.CONSENT_CHANGED, patientId,
                Map.of("patientId", patientId, "consentType", "data_processing", "status", "granted"));
        ActorDirectory.Actor a = actors.findActor(tenant, staffId).orElse(null);
        audit.record(tenant, "staff", staffId, a == null ? "Staff" : a.name(), a == null ? "Staff" : a.role(), ip, "patient.privacy_notice_reacknowledged",
                "patient", patientId, Map.of("noticeVersion", r.acceptedVersion()), Map.of("noticeVersion", r.currentVersion(), "language", r.language()));
        return new Reack(false, r.currentVersion(), r.currentVersion(), r.language());
    }

    /** The locked clauses of the template a notice was built from, for the editor to show greyed. */
    @Transactional(readOnly = true)
    List<String> lockedClauses(String templateKey, String language) {
        return repo.template(templateKey, language).map(NoticeRepository.Template::sections).orElse(List.of());
    }

    @Transactional(readOnly = true)
    Optional<NoticeRepository.Notice> find(String version, String language) {
        return repo.find(version, language);
    }
}
