package com.nabd.hms.nursing;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.nursing.dto.ConsentCaptureResponse;
import com.nabd.hms.nursing.dto.ConsentTemplateCreated;
import com.nabd.hms.nursing.dto.ConsentTemplateList;
import com.nabd.hms.nursing.dto.ConsentTemplateRequest;
import com.nabd.hms.nursing.dto.ConsentTemplateResponse;
import com.nabd.hms.nursing.dto.ConsentWithdrawResponse;
import com.nabd.hms.nursing.dto.ProcedureConsentRequest;
import com.nabd.hms.patient.api.GuardianLookup;
import com.nabd.hms.shared.FileStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.nursing.NursingModels.ProcedureOrderRow;

/**
 * CLN-014: consent forms per procedure and language, signed consent capture (finger signature, witness, guardian for a minor),
 * the tamper-evident PDF (SHA-256 stored, bytes in the tenant's object store) and withdrawal. The status gate is in
 * ProcedureService.updateStatus with the V3140 trigger as backstop.
 */
@Service
public class ConsentService {

    static final int ADULT_AGE = 18;
    private static final int COLS = 90;

    private final ConsentRepository repo;
    private final NursingRepository orders;
    private final ProcedureService procedures;
    private final GuardianLookup guardians;
    private final ActorDirectory actors;
    private final FileStore files;
    private final AuditService audit;
    private final ClinicClock clock;

    ConsentService(ConsentRepository repo, NursingRepository orders, ProcedureService procedures, GuardianLookup guardians, ActorDirectory actors,
                   FileStore files, AuditService audit, ClinicClock clock) {
        this.repo = repo;
        this.orders = orders;
        this.procedures = procedures;
        this.guardians = guardians;
        this.actors = actors;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConsentTemplateList templates(UUID tenantId, String chargeCode, String lang) {
        return new ConsentTemplateList(repo.activeLatest(tenantId, chargeCode, lang).stream().map(ConsentService::toResponse).toList());
    }

    @Transactional
    public ConsentTemplateCreated addTemplate(UUID tenantId, UUID staffId, ConsentTemplateRequest req) {
        orders.findActiveCharge(tenantId, req.chargeCode()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "charge-not-found",
                "Charge not found", "chargeCode does not reference an active charge in this clinic's catalogue."));
        ConsentRepository.Template t;
        try {
            t = repo.insertTemplate(tenantId, req.chargeCode(), req.lang(), req.title().strip(), req.bodyHtml(), staffId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Version conflict", "Another version was added at the same time. Try again.");
        }
        auditStaff(tenantId, staffId, "nursing.consent_template_added", "consent_template", t.id(), null,
                Map.of("chargeCode", t.chargeCode(), "lang", t.lang(), "version", t.version(), "bodySha256", sha256(t.bodyHtml().getBytes(StandardCharsets.UTF_8))));
        return new ConsentTemplateCreated(t.id(), t.version());
    }

    @Transactional
    public ConsentCaptureResponse capture(UUID tenantId, UUID staffId, UUID orderId, ProcedureConsentRequest req) {
        ProcedureOrderRow order = orders.findProcedureOrder(tenantId, orderId).orElseThrow(ApiException::notFound);
        requireOpen(order);
        if (order.consentWithdrawnAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONSENT_WITHDRAWN", "Consent withdrawn",
                    "Consent for this order was withdrawn. Order the procedure again to take a new consent.");
        }
        if (req.templateId() == null) { // NB-119 typed name: only for a procedure the clinic has no consent form for
            if (repo.clinicHasForm(tenantId, order.chargeCode())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TEMPLATE_REQUIRED", "Signed consent form required",
                        "This clinic has a consent form for this procedure: the patient must read and sign it.");
            }
            if (req.signedName() == null || req.signedName().isBlank()) throw invalid("signedName is required.");
            orders.recordConsent(tenantId, order.id(), req.signedName().strip(), staffId);
            auditStaff(tenantId, staffId, "nursing.procedure_consent_recorded", "procedure_order", order.id(), null, Map.of("kind", "typed_name"));
            return new ConsentCaptureResponse(null, null, null, procedures.toResponse(tenantId, orders.findProcedureOrder(tenantId, order.id()).orElseThrow()));
        }
        if (repo.findForOrder(tenantId, orderId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CONSENTED", "Already consented", "A signed consent is already on file for this order.");
        }
        ConsentRepository.Template t = repo.find(tenantId, req.templateId()).filter(x -> x.chargeCode().equals(order.chargeCode()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "template-not-found", "Consent form not found",
                        "templateId is not an active consent form for this procedure."));
        if (t.placeholder() || t.tenantId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TEMPLATE_NOT_APPROVED", "Consent form not set up",
                    "This is a Nabd placeholder, not consent wording. The clinic must add its reviewed consent form first.");
        }
        boolean latest = repo.activeLatest(tenantId, t.chargeCode(), t.lang()).stream().anyMatch(x -> x.id().equals(t.id()));
        if (!latest || req.templateVersion() == null || req.templateVersion() != t.version() || (req.lang() != null && !req.lang().equals(t.lang()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TEMPLATE_VERSION_STALE", "Consent form changed",
                    "A newer version of this consent form is active. Reload it and have it read again before signing.");
        }
        ProcedureConsentRequest.Signer signer = req.signer();
        if (signer == null) throw invalid("signer is required.");
        ConsentRepository.PatientFacts patient = repo.patient(tenantId, order.patientId()).orElseThrow(ApiException::notFound);
        boolean minor = patient.dob() != null && patient.dob().plusYears(ADULT_AGE).isAfter(clock.today(tenantId));
        boolean guardian = "guardian".equals(signer.kind());
        // QA N-3: the client may omit guardianId (a nurse without patients:view cannot read it); the linked guardian is then used
        UUID guardianId = !guardian ? null : signer.guardianId() != null ? signer.guardianId() : patient.guardianId();
        if ((minor && !guardian) || (guardian && (guardianId == null || !guardians.isGuardianOf(guardianId, order.patientId())))) {
            throw new ApiException(HttpStatus.CONFLICT, "GUARDIAN_REQUIRED", "Guardian signature required",
                    "The patient is under " + ADULT_AGE + ": the guardian linked to the patient record must sign.");
        }
        if (req.witnessStaffId() == null) throw invalid("witnessStaffId is required.");
        ActorDirectory.Actor witness = actors.findActor(tenantId, req.witnessStaffId()).orElseThrow(() -> invalid("The witness is not staff of this clinic."));
        List<List<float[]>> strokes;
        List<List<float[]>> assent;
        try {
            strokes = ConsentSignature.parse(req.signatureSvg());
            assent = minor ? ConsentSignature.parse(req.assentSignatureSvg()) : List.of();
        } catch (IllegalArgumentException e) {
            throw invalid("The signature is not valid stroke data.");
        }
        if (!ConsentSignature.enoughInk(strokes) || (!assent.isEmpty() && !ConsentSignature.enoughInk(assent))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_REQUIRED", "Signature required",
                    "Capture a full signature before submitting: a dot or a short tap is not accepted.");
        }

        UUID consentId = UUID.randomUUID();
        Instant signedAt = clock.now().truncatedTo(ChronoUnit.SECONDS);
        String staffName = actors.findActor(tenantId, staffId).map(ActorDirectory.Actor::name).orElse("Unknown staff");
        byte[] pdf = pdf(tenantId, repo.clinicName(tenantId), consentId, order, t, patient.name(), signer, witness.name(), staffName, signedAt, strokes, assent);
        String sha = sha256(pdf);
        // the tenant file store (PLT-029): server-rendered, so stored as available and bound to the patient and visit
        // shortcut: bytes are written before the transaction commits; a rolled-back capture leaves an unreferenced object (its file row rolls back, so it is never served)
        UUID signatureFile = files.storeGenerated(staffId, "consent_form", "image/svg+xml",
                ConsentSignature.toSvg(strokes).getBytes(StandardCharsets.UTF_8), order.patientId(), order.queueEntryId());
        UUID pdfFile = files.storeGenerated(staffId, "consent_form", "application/pdf", pdf, order.patientId(), order.queueEntryId());
        try {
            repo.insertConsent(consentId, tenantId, order.patientId(), order.id(), t, signer.kind(), guardianId,
                    signer.name().strip(), witness.id(), signatureFile, pdfFile, sha, staffId, signedAt);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CONSENTED", "Already consented", "A signed consent is already on file for this order.");
        }
        orders.recordConsent(tenantId, order.id(), signer.name().strip(), staffId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("consentId", consentId);
        after.put("templateId", t.id());
        after.put("templateVersion", t.version());
        after.put("lang", t.lang());
        after.put("signerKind", signer.kind());
        after.put("witnessStaffId", witness.id());
        after.put("pdfSha256", sha);
        auditStaff(tenantId, staffId, "nursing.procedure_consent_signed", "procedure_order", order.id(), null, after);
        return new ConsentCaptureResponse(consentId, pdfFile, sha, procedures.toResponse(tenantId, orders.findProcedureOrder(tenantId, order.id()).orElseThrow()));
    }

    @Transactional
    public ConsentWithdrawResponse withdraw(UUID tenantId, UUID staffId, UUID orderId, String reason) {
        ProcedureOrderRow order = orders.findProcedureOrder(tenantId, orderId).orElseThrow(ApiException::notFound);
        if (order.consentWithdrawnAt() != null) return new ConsentWithdrawResponse("withdrawn"); // a retry is a no-op
        requireOpen(order);
        if (order.consentSignedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CONSENT", "No consent", "There is no recorded consent to withdraw on this order.");
        }
        if (orders.withdrawConsent(tenantId, orderId, staffId, reason.strip())) {
            repo.withdraw(tenantId, orderId, staffId, reason.strip());
            auditStaff(tenantId, staffId, "nursing.procedure_consent_withdrawn", "procedure_order", orderId,
                    Map.of("consent", "signed", "pdfSha256", String.valueOf(order.consentSha256())), Map.of("consent", "withdrawn"));
        }
        return new ConsentWithdrawResponse("withdrawn");
    }

    /** The stored PDF, after checking its bytes still hash to the SHA-256 recorded at signing. */
    @Transactional(readOnly = true)
    public Pdf pdf(UUID tenantId, UUID consentId) {
        ConsentRepository.Consent c = repo.findConsent(tenantId, consentId).orElseThrow(ApiException::notFound);
        byte[] bytes = files.content(c.pdfFileId()).orElseThrow(() -> integrity("The signed PDF is missing from storage."));
        if (!sha256(bytes).equals(c.pdfSha256())) throw integrity("The stored PDF no longer matches the hash recorded at signing.");
        return new Pdf(bytes, c.pdfSha256());
    }

    public record Pdf(byte[] bytes, String sha256) {
    }

    private byte[] pdf(UUID tenantId, String clinicName, UUID consentId, ProcedureOrderRow order, ConsentRepository.Template t, String patientName,
                       ProcedureConsentRequest.Signer signer, String witnessName, String staffName, Instant signedAt,
                       List<List<float[]>> strokes, List<List<float[]>> assent) {
        byte[] form = t.bodyHtml().getBytes(StandardCharsets.UTF_8);
        String at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(signedAt.atZone(clock.zone(tenantId)));
        List<String> lines = new ArrayList<>();
        lines.add(clinicName);
        lines.add("PROCEDURE CONSENT");
        lines.add("");
        lines.addAll(ConsentPdf.wrap("Form: " + t.title() + " (version " + t.version() + ", " + t.lang() + ", id " + t.id() + ")", COLS));
        lines.add("Form text SHA-256: " + sha256(form));
        lines.addAll(ConsentPdf.wrap("Patient: " + patientName + "   Procedure: " + order.chargeName() + " (" + order.chargeCode() + ")", COLS));
        lines.add("Order: " + order.id());
        lines.add("-".repeat(COLS));
        lines.addAll(ConsentPdf.wrap(ConsentPdf.plainText(t.bodyHtml()), COLS));
        lines.add("-".repeat(COLS));
        lines.addAll(ConsentPdf.wrap("Signed by: " + signer.name().strip() + " (" + signer.kind() + ")", COLS));
        lines.add("Signed at: " + at);
        lines.addAll(ConsentPdf.wrap("Witness: " + witnessName + "   Recorded by: " + staffName, COLS));
        lines.add("Consent id: " + consentId);
        lines.addAll(ConsentPdf.wrap("The exact form text is attached to this PDF as consent-form.html. Signatures are on the last page.", COLS));
        List<ConsentPdf.Signature> sigs = new ArrayList<>();
        sigs.add(new ConsentPdf.Signature("Signature of " + signer.kind() + ": " + signer.name().strip(), strokes));
        if (!assent.isEmpty()) sigs.add(new ConsentPdf.Signature("Patient assent: " + patientName, assent));
        return ConsentPdf.render(lines, sigs, "consent-form.html", form);
    }

    private static void requireOpen(ProcedureOrderRow order) {
        if ("completed".equals(order.status()) || "cancelled".equals(order.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "already-final", "Already final", "This procedure is already " + order.status() + ".");
        }
    }

    private void auditStaff(UUID tenantId, UUID staffId, String action, String entityType, UUID entityId, Object before, Object after) {
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElse(null);
        audit.record(tenantId, "staff", staffId, a == null ? "Unknown" : a.name(), a == null ? "Unknown" : a.role(), null, action, entityType, entityId, before, after);
    }

    private static ConsentTemplateResponse toResponse(ConsentRepository.Template t) {
        return new ConsentTemplateResponse(t.id(), t.version(), t.lang(), t.chargeCode(), t.title(), t.bodyHtml(), t.placeholder());
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }

    private static ApiException integrity(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "PDF_INTEGRITY_FAILED", "Consent PDF failed its integrity check", detail);
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
