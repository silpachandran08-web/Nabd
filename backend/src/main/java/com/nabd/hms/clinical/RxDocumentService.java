package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.RxValidity;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.SecureDocumentLinks;
import com.nabd.hms.shared.FileStore;
import com.nabd.hms.shared.rxsign.RxQr;
import com.nabd.hms.shared.rxsign.RxSigningKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CLN-023: the one signed QR token of a signed prescription (issued at sign, or on first print for one signed earlier), the Rx PDF
 * stored once per size with its SHA-256 (re-checked on every download, like CLN-014), sending it as an IDC-015 secure link, and
 * revocation by a licensed prescriber. The token is PHL-013's NRX1 payload (ES256 with RX_KEYS_JSON): ids, times, kid, registration
 * number, no names and no medicines; the QR prints it as the patient-web verify link. Callers bind the tenant (no TenantContext.set).
 */
@Service
@Transactional
public class RxDocumentService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    public record Pdf(byte[] bytes, String sha256) {
    }

    private final PrescriptionPrintRepository repo;
    private final RxValidity validity;
    private final RxSigningKeys keys;
    private final FileStore files;
    private final DrugCoding coding;
    private final ClinicClock clinicClock;
    private final ClinicalAuthority authority;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;
    private final SecureDocumentLinks links;
    private final ObjectMapper json;
    private final String verifyBase;

    RxDocumentService(PrescriptionPrintRepository repo, RxValidity validity, RxSigningKeys keys, FileStore files, DrugCoding coding,
                      ClinicClock clinicClock, ClinicalAuthority authority, AuditService audit, ActorDirectory actors, EventPublisher events,
                      SecureDocumentLinks links, ObjectMapper json, @Value("${app.patient-web-base-url:http://localhost:3810}") String patientWebBase) {
        this.repo = repo;
        this.validity = validity;
        this.keys = keys;
        this.files = files;
        this.coding = coding;
        this.clinicClock = clinicClock;
        this.authority = authority;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
        this.links = links;
        this.json = json;
        this.verifyBase = patientWebBase.replaceAll("/+$", "") + "/rx/";
    }

    /** The token row of a signed prescription, issued now if it has none; the row stays locked until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PrescriptionPrintRepository.Token issue(UUID tenantId, PrescriptionPrintRepository.Header h, UUID queueEntryId) {
        return repo.lockToken(tenantId, h.prescriptionId()).orElseGet(() -> {
            String reg = h.signerRegistrationNo() == null || h.signerRegistrationNo().isBlank() ? null : h.signerRegistrationNo();
            Instant validUntil = validity.validUntil(h.prescriptionId(), queueEntryId);
            String token = RxQr.encode(new RxQr.Claims(tenantId, h.prescriptionId(), h.signedAt(), validUntil, keys.activeKid(), reg), keys);
            repo.insertToken(tenantId, h.prescriptionId(), token, payloadSha256(token), keys.activeKid(), validUntil);
            return repo.lockToken(tenantId, h.prescriptionId()).orElseThrow();
        });
    }

    /** What the printed QR encodes: the public verify page with the token in the path. */
    public String verifyUrl(String token) {
        return verifyBase + token;
    }

    /** SHA-256 (hex) of the signed text, the reference events and the printout carry instead of the token. */
    static String payloadSha256(String token) {
        return sha256(token.substring(0, token.lastIndexOf('.')).getBytes(StandardCharsets.UTF_8));
    }

    /** The stored PDF of this size, rendered and stored on the first request. 409 NOT_SIGNED for a draft. */
    public Pdf pdf(UUID tenantId, UUID staffId, UUID queueEntryId, boolean a5, String ip) {
        PrescriptionPrintRepository.Header h = signed(tenantId, queueEntryId);
        PrescriptionPrintRepository.Token t = issue(tenantId, h, queueEntryId);
        UUID stored = a5 ? t.a5FileId() : t.a4FileId();
        if (stored != null) {
            String sha = a5 ? t.a5Sha256() : t.a4Sha256();
            byte[] bytes = files.content(stored).orElseThrow(() -> integrity("The stored prescription PDF is missing from storage."));
            if (!sha256(bytes).equals(sha)) throw integrity("The stored prescription PDF no longer matches the hash recorded when it was made.");
            return new Pdf(bytes, sha);
        }
        byte[] bytes = RxPdf.render(sheet(tenantId, h, t), a5);
        String sha = sha256(bytes);
        UUID fileId = files.storeGenerated(staffId, "prescription", "application/pdf", bytes, h.patientId(), queueEntryId);
        repo.storePdf(tenantId, h.prescriptionId(), a5, fileId, sha);
        record(tenantId, staffId, ip, "prescription.pdf_stored", h.prescriptionId(), null, Map.of("size", a5 ? "a5" : "a4", "pdfSha256", sha));
        return new Pdf(bytes, sha);
    }

    /** Sends the stored A4 PDF to the patient (or their guardian, by the secure-link rules) as an OTP-protected link. */
    public SecureDocumentLinks.Issued send(UUID tenantId, UUID staffId, UUID queueEntryId, String language, String ip) {
        PrescriptionPrintRepository.Header h = signed(tenantId, queueEntryId);
        pdf(tenantId, staffId, queueEntryId, false, ip);
        PrescriptionPrintRepository.Token t = repo.lockToken(tenantId, h.prescriptionId()).orElseThrow();
        if (t.revokedAt() != null) throw revoked();
        SecureDocumentLinks.Issued issued = links.issueForFile(staffId, ip, t.a4FileId(), h.patientId(), language);
        record(tenantId, staffId, ip, "prescription.pdf_sent", h.prescriptionId(), null, Map.of("linkId", issued.linkId().toString()));
        return issued;
    }

    /**
     * The visit's doctor or a clinical:approve holder who is a licensed clinician (CLN-005's signing rule) revokes a signed prescription:
     * once, with a reason. The signed prescription itself is untouched (V51); scans then answer revoked and the pharmacy refuses it.
     */
    public Instant revoke(UUID tenantId, UUID staffId, boolean canApprove, UUID queueEntryId, String reason, String ip) {
        PrescriptionPrintRepository.Header h = signed(tenantId, queueEntryId);
        authority.require(tenantId, h.doctorId(), staffId, canApprove, "revoke this prescription");
        PrescriptionPrintRepository.Token t = issue(tenantId, h, queueEntryId);
        if (t.revokedAt() != null) throw revoked();
        Instant at = clinicClock.now();
        repo.revoke(tenantId, h.prescriptionId(), reason.strip(), staffId, at);
        record(tenantId, staffId, ip, "prescription.revoked", h.prescriptionId(), Map.of("status", "signed"),
                Map.of("status", "revoked", "reason", reason.strip(), "queueEntryId", queueEntryId.toString()));
        // ids only: the free-text reason stays in the audit row (it may name a clinical detail)
        events.publish(tenantId, DomainEventType.RX_REVOKED, h.prescriptionId(), Map.of("prescriptionId", h.prescriptionId(),
                "queueEntryId", queueEntryId, "revokedBy", staffId, "status", "revoked"));
        return at;
    }

    private PrescriptionPrintRepository.Header signed(UUID tenantId, UUID queueEntryId) {
        PrescriptionPrintRepository.Header h = repo.findHeader(tenantId, queueEntryId).orElseThrow(ApiException::notFound);
        if (!"signed".equals(h.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SIGNED", "Prescription not signed", "Only a signed prescription has a PDF.");
        }
        return h;
    }

    private RxPdf.Sheet sheet(UUID tenantId, PrescriptionPrintRepository.Header h, PrescriptionPrintRepository.Token t) {
        ZoneId zone = clinicClock.zone(tenantId);
        PrescriptionPrintRepository.Person who = h.signerId() == null ? null : repo.findPerson(tenantId, h.signerId()).orElse(null);
        byte[] signature = who == null || who.signatureFileId() == null ? null : files.content(who.signatureFileId()).orElse(null);
        PrescriptionPrintRepository.PatientFacts p = repo.patient(tenantId, h.patientId()).orElseThrow(ApiException::notFound);
        LocalDate issued = h.signedAt().atZone(zone).toLocalDate();
        String age = p.dob() == null ? "-" : Period.between(p.dob(), issued).getYears() + " y";
        boolean genericFirst = coding.genericFirst();
        List<RxPdf.Medicine> meds = new ArrayList<>();
        for (PrescriptionPrintRepository.Item i : repo.items(tenantId, h.prescriptionId())) {
            String details = RxPdf.join("   ", i.route() == null ? null : "Route: " + i.route(), i.dosage() == null ? null : "Dose: " + i.dosage(),
                    i.frequency() == null ? null : "Frequency: " + i.frequency(), i.duration() == null ? null : "Duration: " + i.duration(),
                    i.quantity() == null ? null : "Qty: " + i.quantity().stripTrailingZeros().toPlainString() + (i.quantityUnit() == null ? "" : " " + i.quantityUnit()),
                    // CLN-024: the line's own validity from signing, and its refills when it has any
                    i.validityDays() == null ? null : "Valid until " + DAY.format(h.signedAt().plus(java.time.Duration.ofDays(i.validityDays())).atZone(zone)),
                    i.refillsAllowed() == 0 ? null : "Refills: " + i.refillsAllowed());
            meds.add(RxPdf.medicine(i.drugName(), i.brandName(), details, i.instructions(), genericFirst));
        }
        String reg = h.signerRegistrationNo() == null || h.signerRegistrationNo().isBlank() ? null : h.signerRegistrationNo();
        return new RxPdf.Sheet(h.clinicName(), h.clinicPhone(), who == null ? "Prescriber" : who.name(), reg, who == null ? null : who.council(), signature,
                p.name(), age + " / " + (p.gender() == null ? "-" : p.gender()), DAY.format(issued),
                t.validUntil() == null ? "no expiry set" : DAY.format(t.validUntil().atZone(zone)), allergies(repo.allergySnapshot(tenantId, h.prescriptionId())),
                meds, DAY_TIME.format(h.signedAt().atZone(zone)), verifyUrl(t.rxToken()), t.payloadSha256().substring(0, 12));
    }

    /** DOC-R3: the allergy statement captured at signing, never the register at print time. */
    String allergies(String snapshotJson) {
        if (snapshotJson == null) return "Allergies at signing: not recorded (signed before allergy snapshots).";
        JsonNode n = json.readTree(snapshotJson);
        String state = n.path("state").asString("");
        if ("none_documented".equals(state)) return "Allergies at signing: no known allergies (documented).";
        List<String> list = new ArrayList<>();
        for (JsonNode a : n.path("allergies")) {
            String sev = a.path("severity").asString("");
            list.add(a.path("substance").asString("") + (sev.isEmpty() ? "" : " (" + sev + ")"));
        }
        return list.isEmpty() ? "Allergies at signing: not assessed." : "Allergies at signing: " + String.join(", ", list) + ".";
    }

    private void record(UUID tenantId, UUID staffId, String ip, String action, UUID prescriptionId, Object before, Object after) {
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", staffId, a.name(), a.role(), ip, action, "prescription", prescriptionId, before, after);
    }

    private static ApiException revoked() {
        return new ApiException(HttpStatus.CONFLICT, "prescription-revoked", "Prescription revoked", "This prescription was revoked by the prescriber.");
    }

    private static ApiException integrity(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "PDF_INTEGRITY_FAILED", "Prescription PDF failed its integrity check", detail); // CLN-014's answer
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
