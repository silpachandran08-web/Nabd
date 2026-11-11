package com.nabd.hms.billing.document;

import com.nabd.hms.billing.CheckoutService;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.LineItemResponse;
import com.nabd.hms.clinical.api.ClaimSummary;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.SecureDocumentLinks;
import com.nabd.hms.shared.FileStore;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-035: a reimbursement claim pack for a paid visit: cover sheet, the FIN-012 invoice and receipt (same printed lines), and the
 * minimum-necessary consultation summary from clinical.api (diagnosis, procedures, prescription lines), in the order the format
 * gives; plus a CSV of the invoice lines when the format asks for one. Both are stored in the file store so the IDC-015 secure link
 * can deliver the PDF. Every generation and every send is audited (actor, visit, format, recipient).
 */
@Service
public class ClaimPackService {

    static final String CONTENT_PDF = "application/pdf";
    static final String CONTENT_CSV = "text/csv";
    private static final int COLS = 90;
    private static final int LINES_PER_PAGE = (int) ((841.9f - 2 * 40f) / (9f * 1.25f)); // SimplePdf A4 at 9 pt
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final TypeReference<List<Map<String, Object>>> SECTIONS = new TypeReference<>() { };
    private static final TypeReference<List<String>> COLUMNS = new TypeReference<>() { };

    public record Generated(UUID packId, String documentUrl, String csvUrl) {
    }

    public record FormatView(String code, String insurerName, String jurisdiction, List<String> sections, boolean csv) {
    }

    private final ClaimPackRepository repo;
    private final InvoiceDocumentRepository documents;
    private final InvoiceDocumentService invoiceDocuments;
    private final CheckoutService checkout;
    private final ClaimSummary summaries;
    private final FileStore files;
    private final SecureDocumentLinks links;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ClinicClock clock;
    private final TenantContext tenantContext;
    private final ObjectMapper json;

    ClaimPackService(ClaimPackRepository repo, InvoiceDocumentRepository documents, InvoiceDocumentService invoiceDocuments, CheckoutService checkout,
                     ClaimSummary summaries, FileStore files, SecureDocumentLinks links, AuditService audit, ActorDirectory actors, ClinicClock clock,
                     TenantContext tenantContext, ObjectMapper json) {
        this.repo = repo;
        this.documents = documents;
        this.invoiceDocuments = invoiceDocuments;
        this.checkout = checkout;
        this.summaries = summaries;
        this.files = files;
        this.links = links;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
        this.tenantContext = tenantContext;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<FormatView> formats(String jurisdiction) {
        return repo.formats(tenantContext.current(), jurisdiction).stream()
                .map(f -> new FormatView(f.code(), f.insurerName(), f.jurisdiction(), sections(f).stream().map(s -> String.valueOf(s.get("type"))).toList(),
                        f.csvColumnsJson() != null))
                .toList();
    }

    @Transactional
    public Generated generate(Jwt jwt, UUID visitId, String formatCode) {
        UUID tenantId = tenantContext.current();
        UUID staffId = UUID.fromString(jwt.getSubject());
        ClaimPackRepository.Format format = repo.format(tenantId, formatCode).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "unknown-format", "Unknown format", "formatCode is not an active claim pack format."));
        UUID invoiceId = repo.invoiceForVisit(tenantId, visitId).orElseThrow(ClaimPackService::notBilled);
        InvoiceResponse inv = checkout.getInvoice(tenantId, invoiceId);
        if (!"paid".equals(inv.status())) throw notBilled();

        List<String> missing = new ArrayList<>();
        String registration = inv.doctorId() == null ? null : repo.registrationNo(tenantId, inv.doctorId()).orElse(null);
        String facility = repo.facilityLicence(tenantId, clock.today(tenantId)).orElse(null);
        if (registration == null) missing.add("doctorRegistrationNo");
        if (facility == null) missing.add("facilityLicence");
        if (!missing.isEmpty()) {
            List<String> fix = new ArrayList<>();
            if (registration == null) fix.add("add " + (inv.doctorName() == null ? "the doctor" : inv.doctorName()) + "'s professional registration number in Staff");
            if (facility == null) fix.add("add the clinic's facility licence (current, not expired) in Setup > Licences");
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LICENCE_DATA_MISSING", "Licence data missing",
                    "A claim pack needs the doctor's registration number and the facility licence number: " + String.join("; ", fix) + ".",
                    Map.of("missing", missing));
        }

        List<Map<String, Object>> sections = sections(format);
        ClaimSummary.Summary summary = sections.stream().anyMatch(s -> "summary".equals(s.get("type"))) ? summaries.forVisit(jwt, visitId) : null;
        var supplier = documents.supplier(tenantId, clock.zone(tenantId));
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> s : sections) {
            List<String> part = switch (String.valueOf(s.get("type"))) {
                case "cover" -> cover(format, inv, supplier.legalName(), facility, registration);
                case "invoice" -> invoiceDocuments.lines(tenantId, inv, "invoice", COLS);
                case "receipt" -> inv.payments().isEmpty() ? List.of() : invoiceDocuments.lines(tenantId, inv, "receipt", COLS);
                case "summary" -> summary(s, inv, summary, registration);
                default -> List.of();
            };
            if (part.isEmpty()) continue;
            while (!lines.isEmpty() && lines.size() % LINES_PER_PAGE != 0) lines.add(""); // each section starts on a new page
            lines.addAll(part);
        }
        byte[] pdf = SimplePdf.render(lines, 595.3f, 841.9f, 40f, 9f, false);
        UUID documentId = files.storeGenerated(staffId, "claim_pack", CONTENT_PDF, pdf, inv.patientId(), visitId);
        UUID csvId = format.csvColumnsJson() == null ? null
                : files.storeGenerated(staffId, "claim_pack", CONTENT_CSV, csv(inv, documents.sacHsn(tenantId, invoiceId), json.readValue(format.csvColumnsJson(), COLUMNS)), inv.patientId(), visitId);
        String sha = sha256(pdf);
        UUID packId = repo.insert(tenantId, visitId, invoiceId, inv.patientId(), format.code(), documentId, csvId, sha, staffId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("visitId", visitId);
        after.put("invoiceId", invoiceId);
        after.put("formatCode", format.code());
        after.put("recipient", "desk");
        after.put("csv", csvId != null);
        after.put("pdfSha256", sha);
        auditStaff(tenantId, staffId, null, "billing.claim_pack_generated", packId, after);
        String base = "/v1/billing/claim-packs/" + packId;
        return new Generated(packId, base + "/document", csvId == null ? null : base + "/document.csv");
    }

    /** The stored PDF or CSV. The pack carries the consultation summary, so the same clinical read grant and gate apply. */
    @Transactional(readOnly = true)
    public byte[] document(Jwt jwt, UUID packId, boolean csv) {
        ClaimPackRepository.Pack p = repo.find(tenantContext.current(), packId).orElseThrow(ApiException::notFound);
        summaries.forVisit(jwt, p.visitId());
        UUID fileId = csv ? p.csvDocumentId() : p.documentId();
        if (fileId == null) throw ApiException.notFound("This claim pack has no CSV.");
        return files.content(fileId).orElseThrow(ApiException::notFound);
    }

    /** OTP-protected secure link to the PDF, to the patient (default) or their guardian / GUARDIAN-scope caregiver. */
    @Transactional
    public SecureDocumentLinks.Issued send(Jwt jwt, String ip, UUID packId, UUID recipientPatientId, String language) {
        UUID tenantId = tenantContext.current();
        UUID staffId = UUID.fromString(jwt.getSubject());
        ClaimPackRepository.Pack p = repo.find(tenantId, packId).orElseThrow(ApiException::notFound);
        summaries.forVisit(jwt, p.visitId());
        UUID recipient = recipientPatientId == null ? p.patientId() : recipientPatientId;
        SecureDocumentLinks.Issued issued = links.issueForFile(staffId, ip, p.documentId(), recipient, language);
        auditStaff(tenantId, staffId, ip, "billing.claim_pack_sent", packId, Map.of("visitId", p.visitId(), "formatCode", p.formatCode(),
                "recipient", "patient", "recipientPatientId", recipient, "secureLinkId", issued.linkId()));
        return issued;
    }

    private List<String> cover(ClaimPackRepository.Format f, InvoiceResponse inv, String clinic, String facility, String registration) {
        String rule = "=".repeat(COLS);
        List<String> l = new ArrayList<>();
        l.add(rule);
        l.add("CLAIM PACK - " + f.insurerName());
        l.add(rule);
        l.add("Clinic: " + clinic);
        l.add("Facility licence no: " + facility);
        l.add("Patient: " + nz(inv.patientName()));
        l.add("Visit date: " + DATE.format(inv.createdAt().atZone(clock.zone(tenantContext.current()))));
        l.add("Treating doctor: " + nz(inv.doctorName()) + "   Registration no: " + registration);
        l.add("Invoice: " + nz(inv.invoiceNumber()) + "   Total: " + inv.currency() + " " + inv.total().toPlainString()
                + "   Paid: " + inv.currency() + " " + inv.paid().toPlainString());
        l.add("Format: " + f.code());
        l.add("");
        l.add("Contents: invoice, payment receipt and consultation summary (diagnosis, procedures, prescription only).");
        return l;
    }

    @SuppressWarnings("unchecked")
    private List<String> summary(Map<String, Object> section, InvoiceResponse inv, ClaimSummary.Summary s, String registration) {
        List<String> fields = section.get("fields") instanceof List<?> f ? (List<String>) f : List.of();
        List<String> l = new ArrayList<>();
        l.add("CONSULTATION SUMMARY (for insurance claim)");
        l.add("-".repeat(COLS));
        l.add("Doctor: " + nz(inv.doctorName()) + "   Registration no: " + registration);
        if (fields.contains("diagnosis")) {
            l.add("Diagnosis:");
            l.addAll(indent(s.diagnosis() == null || s.diagnosis().isBlank() ? "Not recorded in a signed note" : s.diagnosis()));
        }
        if (fields.contains("procedures")) {
            l.add("Procedures:");
            List<String> procs = inv.lineItems().stream().filter(li -> "Procedure".equalsIgnoreCase(li.category()))
                    .map(li -> "- " + li.chargeName() + (li.quantity() > 1 ? " x" + li.quantity() : "")).toList();
            l.addAll(procs.isEmpty() ? List.of("  None billed") : procs);
        }
        if (fields.contains("medicines")) {
            l.add("Prescription:");
            l.addAll(s.medicines().isEmpty() ? List.of(s.prescriptionSigned() ? "  No items" : "  No signed prescription")
                    : s.medicines().stream().map(m -> "- " + m).toList());
        }
        return l;
    }

    private static byte[] csv(InvoiceResponse inv, List<String> sacHsn, List<String> columns) {
        StringBuilder b = new StringBuilder(String.join(",", columns)).append("\r\n");
        int n = 0;
        for (LineItemResponse li : inv.lineItems()) {
            n++;
            List<String> row = new ArrayList<>();
            for (String c : columns) {
                row.add(cell(switch (c) {
                    case "invoiceNumber" -> inv.invoiceNumber();
                    case "invoiceDate" -> inv.createdAt().toString();
                    case "lineNo" -> String.valueOf(n);
                    case "description" -> li.chargeName();
                    case "sacHsn" -> n <= sacHsn.size() ? sacHsn.get(n - 1) : null; // same display order as the lines
                    case "quantity" -> String.valueOf(li.quantity());
                    case "unitPrice" -> li.unitPrice().toPlainString();
                    case "taxRatePercent" -> li.taxRatePercent().toPlainString();
                    case "lineTotal" -> li.lineTotal().toPlainString();
                    default -> null;
                }));
            }
            b.append(String.join(",", row)).append("\r\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 4180 quoting, and a leading quote on text a spreadsheet would run as a formula. */
    static String cell(String v) {
        if (v == null) return "";
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?") ? "'" + v : v;
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private List<Map<String, Object>> sections(ClaimPackRepository.Format f) {
        return json.readValue(f.sectionsJson(), SECTIONS);
    }

    private static List<String> indent(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String rest = line.strip();
            while (rest.length() > COLS - 2) {
                int cut = rest.lastIndexOf(' ', COLS - 2);
                if (cut <= 0) cut = COLS - 2;
                out.add("  " + rest.substring(0, cut));
                rest = rest.substring(cut).strip();
            }
            out.add("  " + rest);
        }
        return out;
    }

    private void auditStaff(UUID tenantId, UUID staffId, String ip, String action, UUID packId, Map<String, Object> after) {
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElse(null);
        audit.record(tenantId, "staff", staffId, a == null ? "Unknown" : a.name(), a == null ? "Unknown" : a.role(), ip, action, "claim_pack", packId, null, after);
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private static ApiException notBilled() {
        return new ApiException(HttpStatus.CONFLICT, "VISIT_NOT_BILLED", "Visit not billed",
                "A claim pack needs the visit's invoice to be fully paid. Finish checkout and payment first.");
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
