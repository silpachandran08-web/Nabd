package com.nabd.hms.clinical.derm;

import com.nabd.hms.clinical.ClinicalReadScope;
import com.nabd.hms.clinical.ReadScope;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.shared.ConsentSignature;
import com.nabd.hms.shared.FileStore;
import com.nabd.hms.shared.HiddenFiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CLN-047 (FRD DERM-01): photo consent first, then dated photos per body site and visit, each with its consent badge. A photo
 * is an upload of the PLT-029 pipeline (purpose clinical_photo: scanned, EXIF stripped, <= 1.5 MB) bound to the patient and
 * visit; the timeline hands out 5-minute signed URLs only. Withdrawing the consent hides every photo (kept, never deleted) and
 * blocks new ones until a new consent is recorded; photos hidden by a withdrawal stay hidden. Image metadata is never logged
 * or audited: audit rows carry ids, scope and site only.
 */
@Service
public class DermPhotoService implements HiddenFiles {

    static final String PURPOSE = "clinical_photo";

    private final DermPhotoRepository repo;
    private final ClinicalReadScope scopes;
    private final FileStore files;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;
    private final PatientRefs refs;
    private final ClinicClock clock;
    private final ObjectMapper json;

    DermPhotoService(DermPhotoRepository repo, ClinicalReadScope scopes, FileStore files, AuditService audit, ActorDirectory actors,
                     EventPublisher events, PatientRefs refs, ClinicClock clock, ObjectMapper json) {
        this.repo = repo;
        this.scopes = scopes;
        this.files = files;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
        this.refs = refs;
        this.clock = clock;
        this.json = json;
    }

    /** Idempotent: the same scope again returns the active consent; a different scope is 409 (withdraw first). */
    @Transactional
    UUID recordConsent(ReadScope scope, UUID patientId, String consentScope, String signerKind, String signatureSvg, String ip) {
        scopes.requirePatient(scope, patientId);
        UUID tenantId = scope.tenantId();
        var active = repo.activeConsent(tenantId, patientId);
        if (active.isPresent()) {
            if (active.get().scope().equals(consentScope)) return active.get().id();
            throw new ApiException(HttpStatus.CONFLICT, "photo-consent-active", "Photo consent already recorded",
                    "This patient already has an active photo consent with another scope. Record the withdrawal first, then the new consent.",
                    Map.of("code", "PHOTO_CONSENT_ACTIVE"));
        }
        UUID signatureFile = null;
        if (signatureSvg != null) {
            List<List<float[]>> strokes;
            try {
                strokes = ConsentSignature.parse(signatureSvg);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-signature", "Invalid signature", "The signature is not valid stroke data.",
                        Map.of("code", "SIGNATURE_INVALID"));
            }
            if (!ConsentSignature.enoughInk(strokes)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "signature-required", "Signature required",
                        "Capture a full signature before submitting: a dot or a short tap is not accepted.", Map.of("code", "SIGNATURE_REQUIRED"));
            }
            signatureFile = files.storeGenerated(scope.staffId(), "consent_form", "image/svg+xml",
                    ConsentSignature.toSvg(strokes).getBytes(StandardCharsets.UTF_8), patientId, null);
        }
        UUID id;
        try {
            id = repo.insertConsent(tenantId, patientId, consentScope, signerKind, signatureFile, scope.staffId(), clock.now());
        } catch (DuplicateKeyException e) { // a concurrent capture won: same answer as the idempotent repeat
            throw new ApiException(HttpStatus.CONFLICT, "photo-consent-active", "Photo consent already recorded",
                    "A photo consent was recorded for this patient at the same moment. Reload to see it.", Map.of("code", "PHOTO_CONSENT_ACTIVE"));
        }
        record(scope, ip, "derm_photo_consent.recorded", "photo_consents", id, null,
                Map.of("patientId", patientId.toString(), "scope", consentScope, "signerKind", signerKind, "signed", signatureFile != null));
        return id;
    }

    /** Withdraws the active consent and hides the patient's photos; 404 when there is no active consent. */
    @Transactional
    int withdrawConsent(ReadScope scope, UUID patientId, String ip) {
        scopes.requirePatient(scope, patientId);
        UUID tenantId = scope.tenantId();
        Instant now = clock.now();
        UUID id = repo.withdraw(tenantId, patientId, scope.staffId(), now)
                .orElseThrow(() -> ApiException.notFound("This patient has no active photo consent."));
        int hidden = repo.hideAll(tenantId, patientId, now);
        record(scope, ip, "derm_photo_consent.withdrawn", "photo_consents", id, Map.of("status", "active"),
                Map.of("status", "withdrawn", "photosHidden", hidden));
        return hidden;
    }

    @Transactional
    UUID addPhoto(ReadScope scope, UUID patientId, UUID uploadId, UUID queueEntryId, DermPhotoController.Site site, String note, String ip) {
        scopes.requirePatient(scope, patientId);
        UUID tenantId = scope.tenantId();
        if (!repo.visitPatient(tenantId, queueEntryId).map(patientId::equals).orElse(false)) {
            throw ApiException.notFound("This visit is not one of this patient's visits.");
        }
        var consent = repo.activeConsent(tenantId, patientId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "photo-consent-required", "Photo consent required", "Record the patient's photo consent before taking photos.",
                Map.of("code", "PHOTO_CONSENT_REQUIRED")));
        var existing = repo.byUpload(tenantId, uploadId);
        if (existing.isPresent()) { // idempotent repeat of the same registration
            if (existing.get()[1].equals(patientId)) return existing.get()[0];
            throw notClean();
        }
        FileStore.FileInfo f = files.info(uploadId).orElseThrow(ApiException::notFound);
        if (!"available".equals(f.status()) || f.contentType() == null || !f.contentType().startsWith("image/")
                || !files.bind(uploadId, patientId, queueEntryId)) {
            throw notClean(); // pending scan, blocked, not an image, or another patient's file: never viewable here
        }
        Instant now = clock.now();
        UUID id;
        try {
            id = repo.insertPhoto(tenantId, patientId, queueEntryId, uploadId, json.writeValueAsString(site), note, consent.id(), scope.staffId(), now);
        } catch (DuplicateKeyException e) { // a concurrent registration of the same upload won (the transaction is now aborted)
            throw new ApiException(HttpStatus.CONFLICT, "photo-already-registered", "Photo already registered",
                    "This photo was registered at the same moment. Reload the timeline.", Map.of("code", "PHOTO_ALREADY_REGISTERED"));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("patientId", patientId.toString());
        after.put("queueEntryId", queueEntryId.toString());
        after.put("uploadId", uploadId.toString());
        after.put("consentId", consent.id().toString());
        after.put("region", site.region());
        after.put("side", site.side());
        record(scope, ip, "derm_photo.added", "clinical_photos", id, null, after); // no note (free text), no image metadata
        events.publish(tenantId, DomainEventType.SPECIALTY_PHOTO_ADDED, id, Map.of("photoId", id, "patientRef", refs.refFor(patientId)));
        return id;
    }

    @Transactional(readOnly = true)
    DermPhotoController.Timeline timeline(ReadScope scope, UUID patientId, String region) {
        scopes.requirePatient(scope, patientId);
        UUID tenantId = scope.tenantId();
        DermPhotoController.Consent consent = repo.activeConsent(tenantId, patientId)
                .map(c -> new DermPhotoController.Consent(c.id(), c.scope(), c.status(), c.signerKind(), c.recordedAt())).orElse(null);
        Map<String, List<DermPhotoController.Photo>> bySite = new LinkedHashMap<>();
        Map<String, DermPhotoController.Site> sites = new HashMap<>();
        for (DermPhotoRepository.PhotoRow r : repo.visible(tenantId, patientId, region)) {
            DermPhotoController.Site site = json.readValue(r.siteJson(), DermPhotoController.Site.class);
            String k = site.region() + "|" + site.side() + "|" + (site.mapPointId() == null ? "" : site.mapPointId());
            sites.putIfAbsent(k, site);
            FileStore.SignedUrl u;
            try {
                u = files.downloadUrl(r.uploadId());
            } catch (ApiException e) {
                continue; // the file is no longer available (removed): the row stays, it just has nothing to show
            }
            bySite.computeIfAbsent(k, x -> new ArrayList<>()).add(new DermPhotoController.Photo(r.id(), r.takenAt(), r.queueEntryId(),
                    r.visitDate(), site, r.note(), u.url(), u.expiresAt(), r.consentScope()));
        }
        List<DermPhotoController.SitePhotos> out = new ArrayList<>();
        bySite.forEach((k, photos) -> out.add(new DermPhotoController.SitePhotos(sites.get(k), photos)));
        return new DermPhotoController.Timeline(consent, repo.hiddenCount(tenantId, patientId), out);
    }

    /** True when the file is a photo hidden by a withdrawn consent: no new download-url, and the blob endpoint stops serving earlier links. */
    @Override
    @Transactional(readOnly = true)
    public boolean hidden(UUID tenantId, UUID fileId) {
        return repo.hiddenUpload(tenantId, fileId);
    }

    private void record(ReadScope scope, String ip, String action, String entity, UUID id, Map<String, Object> before, Map<String, Object> after) {
        ActorDirectory.Actor actor = actors.findActor(scope.tenantId(), scope.staffId()).orElseThrow(ApiException::notFound);
        audit.record(scope.tenantId(), "staff", scope.staffId(), actor.name(), actor.role(), ip, action, entity, id, before, after);
    }

    private static ApiException notClean() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "upload-not-clean", "Upload not ready",
                "The photo is still being checked, was blocked, or is not an image of this patient. Take it again.",
                Map.of("code", "UPLOAD_NOT_CLEAN"));
    }
}
