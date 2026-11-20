package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.dto.PaperRxDtos;
import com.nabd.hms.shared.FileStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PHL-020: an outside (paper or WhatsApp-photo) prescription recorded at the counter, and the part of the Rx gate that lets a
 * prescription-only line be billed against it. The photo is a PLT-029 upload of purpose paper_rx: scanned before it can be
 * linked, invisible to every generic file path, and readable only here through a 60-second signed URL whose issue is audited.
 * Which classes keep a duplicate copy and how long an Rx stays valid are pharmacy_schedule_rules data, not decided here.
 * Audit rows carry ids, counts and flags only: no prescriber, customer or drug text.
 */
@Service
public class PaperRxService {

    static final String PURPOSE = FileStore.PRIVATE_PURPOSE;
    static final Duration PHOTO_URL_TTL = Duration.ofSeconds(60);

    /** Why the gate refuses a paper-linked bill line. */
    record Refusal(RxGate.Line line, String type, String title, String detail) {
    }

    private final PaperRxRepository repo;
    private final RxGateRepository bills;
    private final PharmacyRepository items;
    private final FileStore files;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final TenantContext tenant;
    private final ClinicClock clock;

    PaperRxService(PaperRxRepository repo, RxGateRepository bills, PharmacyRepository items, FileStore files, AuditService audit,
                   ActorDirectory actors, TenantContext tenant, ClinicClock clock) {
        this.repo = repo;
        this.bills = bills;
        this.items = items;
        this.files = files;
        this.audit = audit;
        this.actors = actors;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional
    public PaperRxDtos.UploadSlot initUpload(UUID staffId, PaperRxDtos.UploadRequest req) {
        FileStore.UploadSlot s = files.initUpload(staffId, PURPOSE, req.contentType(), req.sizeBytes());
        return new PaperRxDtos.UploadSlot(s.uploadId(), s.putUrl(), s.maxBytes());
    }

    @Transactional
    public PaperRxDtos.UploadStatus completeUpload(UUID uploadId) {
        return new PaperRxDtos.UploadStatus(files.completeUpload(uploadId, PURPOSE), "pending_scan");
    }

    @Transactional(readOnly = true)
    public PaperRxDtos.UploadStatus uploadStatus(UUID fileId) {
        return new PaperRxDtos.UploadStatus(fileId, files.statusOf(fileId, PURPOSE).orElseThrow(ApiException::notFound));
    }

    @Transactional
    public PaperRxDtos.Response create(UUID staffId, String ip, PaperRxDtos.CreateRequest req) {
        UUID t = tenant.current();
        LocalDate today = clock.today(t);
        if (req.rxDate().isAfter(today)) {
            throw invalid("The prescription date cannot be in the future.");
        }
        String market = items.tenantMarket(t);
        boolean keepsCopy = false;
        Integer days = null;
        for (PaperRxDtos.LineRequest l : req.lines()) {
            if (l.itemId() == null) {
                if (l.drugText() == null || l.drugText().isBlank()) throw invalid("Each line needs an item or the drug as written on the prescription.");
                continue;
            }
            PaperRxRepository.ItemRule rule = repo.itemRule(t, market, l.itemId())
                    .orElseThrow(() -> invalid("A line names an item that is not in this clinic's price list."));
            keepsCopy |= rule.duplicateRxCopy();
            if (rule.validityDays() != null && (days == null || rule.validityDays() < days)) days = rule.validityDays();
        }
        if (keepsCopy && (!req.duplicateCopyRetained() || req.documentId() == null)) {
            throw copyRequired();
        }
        LocalDate validUntil = days == null ? null : req.rxDate().plusDays(days);
        if (validUntil != null && validUntil.isBefore(today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "rx-expired", "Prescription expired",
                    "This prescription has passed its validity date and can no longer be dispensed.");
        }
        if (req.documentId() != null) {
            String status = files.statusOf(req.documentId(), PURPOSE).orElse("missing");
            if (!"available".equals(status)) throw notClean(status);
        }
        UUID id;
        try {
            id = repo.insert(t, req, validUntil, staffId);
        } catch (DuplicateKeyException e) { // single use: one photo backs one record
            throw new ApiException(HttpStatus.CONFLICT, "document-already-used", "Photo already used",
                    "This photo is already attached to another paper prescription. Take a new photo.");
        }
        List<PaperRxDtos.Line> lines = new ArrayList<>();
        for (PaperRxDtos.LineRequest l : req.lines()) {
            lines.add(new PaperRxDtos.Line(repo.insertLine(t, id, l), l.itemId(), l.itemId() == null ? l.drugText().trim() : null, l.qty()));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("lines", lines.size());
        after.put("hasPhoto", req.documentId() != null);
        after.put("duplicateCopyRetained", req.duplicateCopyRetained());
        audit(t, staffId, ip, "pharmacy.paper_rx.recorded", id, after);
        return new PaperRxDtos.Response(id, validUntil, lines);
    }

    /** Every issued URL is one audited view. 404 for another clinic's record, a record without a photo, or a photo that is no longer available. */
    @Transactional
    public PaperRxDtos.PhotoUrl photoUrl(UUID paperRxId, UUID staffId, String ip) {
        UUID t = tenant.current();
        UUID doc = repo.documentOf(t, paperRxId).orElseThrow(ApiException::notFound);
        FileStore.SignedUrl u = files.downloadUrl(doc, PHOTO_URL_TTL);
        audit(t, staffId, ip, "pharmacy.paper_rx.photo_viewed", paperRxId, Map.of("documentId", doc.toString()));
        return new PaperRxDtos.PhotoUrl(u.url(), u.expiresAt());
    }

    @Transactional(readOnly = true)
    public List<PaperRxDtos.Prescriber> recentPrescribers() {
        return repo.recentPrescribers(tenant.current());
    }

    /**
     * The gate's check of the bill lines linked to a paper prescription line; runs inside the checkout transaction (RxGate.check).
     * Everything is re-checked at sale time: the record may be older than a rule, a void, or a file status change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Refusal> refusal(UUID tenantId, List<RxGate.Line> lines) {
        Map<UUID, Integer> requested = new LinkedHashMap<>();
        for (RxGate.Line l : lines) {
            if (l.paperRxLineId() != null) requested.merge(l.paperRxLineId(), l.quantity(), Integer::sum);
        }
        if (requested.isEmpty()) return Optional.empty();
        Map<UUID, PaperRxRepository.LineRow> rows = repo.lockLines(tenantId, requested.keySet());
        Map<UUID, Long> dispensed = bills.paperDispensed(tenantId, requested.keySet()); // after the lock: sees committed fills
        String market = items.tenantMarket(tenantId);
        LocalDate today = clock.today(tenantId);
        for (RxGate.Line l : lines) {
            if (l.paperRxLineId() == null) continue;
            PaperRxRepository.LineRow row = rows.get(l.paperRxLineId());
            if (row == null) { // unknown or another tenant's id: same answer, nothing leaked
                return refuse(l, "rx-required", "Prescription required",
                        "The linked paper prescription line for " + l.chargeCode() + " was not found. Record the paper prescription again.");
            }
            if (row.itemId() != null && !row.itemId().equals(l.catalogueId())) {
                return refuse(l, "rx-item-mismatch", "Prescription is for another item",
                        "The linked paper prescription line was recorded for a different item than " + l.chargeCode() + ".");
            }
            PaperRxRepository.ItemRule rule = l.catalogueId() == null ? null : repo.itemRule(tenantId, market, l.catalogueId()).orElse(null);
            boolean lapsed = row.validUntil() != null && row.validUntil().isBefore(today)
                    || rule != null && rule.validityDays() != null && row.rxDate().plusDays(rule.validityDays()).isBefore(today);
            if (lapsed) {
                return refuse(l, "rx-expired", "Prescription expired",
                        "The paper prescription for " + l.chargeCode() + " has passed its validity date and can no longer be dispensed.");
            }
            if (rule != null && rule.duplicateRxCopy() && (!row.duplicateCopyRetained() || row.documentId() == null)) {
                return refuse(l, "rx-copy-required", "Duplicate copy required", copyRequired().getMessage());
            }
            if (row.documentId() != null) {
                String status = files.statusOf(row.documentId(), PURPOSE).orElse("missing");
                if (!"available".equals(status)) return refuse(l, "document-not-clean", "Photo not usable", notClean(status).getMessage());
            }
            long done = dispensed.getOrDefault(row.id(), 0L);
            if (done + requested.get(row.id()) > row.qty()) {
                return refuse(l, "rx-quantity-exhausted", "Prescription quantity used up",
                        "The linked paper prescription line for " + l.chargeCode() + " has " + Math.max(0, row.qty() - done)
                                + " left; this bill asks for " + requested.get(row.id()) + ".");
            }
        }
        return Optional.empty();
    }

    private static Optional<Refusal> refuse(RxGate.Line l, String type, String title, String detail) {
        return Optional.of(new Refusal(l, type, title, detail));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }

    private static ApiException copyRequired() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "rx-copy-required", "Duplicate copy required",
                "This item's schedule class needs the duplicate copy of the prescription retained and a photo of it. Tick 'duplicate copy retained' and attach the photo.");
    }

    /** 422 document-not-clean, saying why: the scan result is the reason the sale cannot go on. */
    private static ApiException notClean(String status) {
        String why = switch (status) {
            case "infected" -> "The photo failed the virus scan and was blocked. Take a new photo.";
            case "rejected_type" -> "The file is not a real JPEG, PNG or PDF and was blocked. Take a new photo.";
            case "awaiting_upload", "pending_scan" -> "The photo is still being checked. Try again in a moment.";
            default -> "The photo is not available. Take a new photo.";
        };
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "document-not-clean", "Photo not usable", why, Map.of("scanStatus", status));
    }

    private void audit(UUID tenantId, UUID staffId, String ip, String action, UUID paperRxId, Map<String, Object> after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, action, "paper_prescription", paperRxId, null, after);
    }
}
