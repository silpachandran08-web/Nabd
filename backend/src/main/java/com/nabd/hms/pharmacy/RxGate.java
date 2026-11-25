package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-001 (hazard H-04): the counter and the visit checkout may bill a prescription-only item (charge_catalogue.is_rx,
 * set by the clinic) only against a signed prescription line of the same tenant and patient with quantity left.
 * Which items are prescription-only is the clinic's data, not decided here. A refusal is 422 Problem+JSON with a
 * stable type (rx-required, rx-not-signed, rx-patient-mismatch, rx-quantity-exhausted, PHL-012 rx-expired, CLN-023 rx-revoked,
 * CLN-024 REFILLS_EXHAUSTED and rx-expired for a line past its own validity) plus an audit row and event
 * that survive the rolled-back checkout. PHL-020: a line may instead point at a recorded outside (paper/photo) prescription line
 * (paperRxLineId); PaperRxService checks those (rx-item-mismatch, rx-copy-required, document-not-clean, rx-expired, rx-quantity-exhausted).
 */
@Service
public class RxGate {

    /** One bill line as the gate sees it. */
    public record Line(String chargeCode, UUID catalogueId, boolean isRx, int quantity, UUID prescriptionItemId, UUID paperRxLineId) {
    }

    /** PHL-023: one counter-sale line with the seller's second confirmation (null when none was given). */
    public record CounterLine(String chargeCode, UUID catalogueId, String ackReason) {
    }

    /** refills: dispenses allowed after the first (CLN-024); lapsed: the line's own validity has passed. */
    record RxItemRow(UUID id, UUID patientId, String status, BigDecimal quantity, boolean revoked, int refills, boolean lapsed) {
    }

    public record RxLineResponse(UUID prescriptionItemId, UUID patientId, String patientName, String drugName, String dosage,
                                 BigDecimal quantity, BigDecimal remainingQty, Instant signedAt) {
    }

    private final RxGateRepository repo;
    private final RxGateBlockRecorder recorder;
    private final com.nabd.hms.common.TenantContext tenantContext;
    private final PharmacyWorklistRepository worklist;
    private final PaperRxService paper;
    private final ScheduleCounterService counter;

    RxGate(RxGateRepository repo, RxGateBlockRecorder recorder, com.nabd.hms.common.TenantContext tenantContext, PharmacyWorklistRepository worklist,
           PaperRxService paper, ScheduleCounterService counter) {
        this.counter = counter;
        this.paper = paper;
        this.worklist = worklist;
        this.repo = repo;
        this.recorder = recorder;
        this.tenantContext = tenantContext;
    }

    /**
     * Call inside the checkout transaction, before any line is written. Locks the linked prescription lines (FOR UPDATE,
     * id order) so two checkouts racing on one line run one after the other and the second sees the first's fill.
     *
     * @param patientId the visit's patient, or null for a counter sale (then every linked line must share one patient)
     * @return the patient the linked lines belong to (null when nothing is linked)
     */
    @Transactional
    public UUID check(UUID tenantId, UUID patientId, List<Line> lines, UUID staffId, String ip) {
        tenantContext.set(tenantId);
        for (Line l : lines) {
            if (l.prescriptionItemId() != null && l.paperRxLineId() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                        "Link " + l.chargeCode() + " to a signed prescription line or to a paper prescription, not both.");
            }
            if (l.isRx() && l.prescriptionItemId() == null && l.paperRxLineId() == null) {
                block(tenantId, staffId, ip, l, "rx-required", "Prescription required",
                        l.chargeCode() + " is prescription-only. Link it to a signed prescription line before billing it.");
            }
        }
        paper.refusal(tenantId, lines).ifPresent(r -> block(tenantId, staffId, ip, r.line(), r.type(), r.title(), r.detail())); // PHL-020
        Map<UUID, Integer> requested = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.prescriptionItemId() != null) requested.merge(l.prescriptionItemId(), l.quantity(), Integer::sum);
        }
        if (requested.isEmpty()) return null;

        Map<UUID, RxItemRow> rows = repo.lockItems(tenantId, requested.keySet());
        Map<UUID, BigDecimal> dispensed = repo.dispensed(tenantId, requested.keySet()); // after the lock: sees committed fills
        UUID owner = patientId;
        for (Line l : lines) {
            if (l.prescriptionItemId() == null) continue;
            RxItemRow row = rows.get(l.prescriptionItemId());
            if (row == null) { // unknown or another tenant's id: same answer, nothing leaked
                block(tenantId, staffId, ip, l, "rx-required", "Prescription required",
                        "The linked prescription line for " + l.chargeCode() + " was not found. Link a signed prescription line.");
            }
            if (!"signed".equals(row.status())) {
                block(tenantId, staffId, ip, l, "rx-not-signed", "Prescription not signed",
                        "The linked prescription for " + l.chargeCode() + " is still a draft. The doctor must sign it first.");
            }
            if (row.revoked()) { // CLN-023: the prescriber revoked it after signing
                block(tenantId, staffId, ip, l, "rx-revoked", "Prescription revoked",
                        "The prescription for " + l.chargeCode() + " was revoked by the prescriber and can no longer be dispensed.");
            }
            if (owner == null) owner = row.patientId();
            else if (!owner.equals(row.patientId())) {
                block(tenantId, staffId, ip, l, "rx-patient-mismatch", "Prescription is for another patient",
                        "The linked prescription line for " + l.chargeCode() + " belongs to a different patient.");
            }
        }
        java.util.Set<UUID> lapsed = worklist.lapsedLines(tenantId, requested.keySet().toArray(UUID[]::new)); // PHL-012: In-house Rx validity
        for (Line l : lines) {
            if (l.prescriptionItemId() != null && (lapsed.contains(l.prescriptionItemId()) || rows.get(l.prescriptionItemId()).lapsed())) { // CLN-024: per-line validity
                block(tenantId, staffId, ip, l, "rx-expired", "Prescription expired",
                        "The prescription for " + l.chargeCode() + " has passed its validity date and can no longer be dispensed.");
            }
        }
        for (Line l : lines) {
            if (l.prescriptionItemId() == null) continue;
            RxItemRow row = rows.get(l.prescriptionItemId());
            BigDecimal done = dispensed.getOrDefault(row.id(), BigDecimal.ZERO);
            BigDecimal after = done.add(BigDecimal.valueOf(requested.get(row.id())));
            BigDecimal allowed = allowance(row.quantity(), row.refills());
            // A line with no prescribed quantity has nothing to dispense against (fail safe).
            if (allowed == null || after.compareTo(allowed) > 0) {
                // CLN-024: the first fill is used up and every refill too: the pharmacist's answer is "no refills left"
                if (row.refills() > 0 && done.compareTo(allowed) >= 0) {
                    block(tenantId, staffId, ip, l, "REFILLS_EXHAUSTED", "No refills left",
                            "The prescription for " + l.chargeCode() + " has been dispensed with all " + row.refills() + " refills.");
                }
                block(tenantId, staffId, ip, l, "rx-quantity-exhausted", "Prescription quantity used up",
                        "The linked prescription line for " + l.chargeCode() + " has "
                                + remaining(allowed, dispensed.get(row.id())).toPlainString() + " left; this bill asks for "
                                + requested.get(row.id()) + ".");
            }
        }
        return owner;
    }

    /**
     * PHL-023, counter sale only, inside its transaction and before anything is written: 422 schedule-ack-required for a line whose
     * schedule class needs the second confirmation and has no reason.
     *
     * @return call it with the invoice id once the invoice exists (same transaction): it audits the confirmations and, when no
     * registered pharmacist is on duty, that fact
     */
    public java.util.function.Consumer<UUID> checkCounterSale(UUID tenantId, List<CounterLine> lines, UUID staffId, String ip) {
        ScheduleCounterService.Checked checked = counter.check(tenantId, lines, staffId, ip);
        return invoiceId -> counter.record(tenantId, invoiceId, checked, staffId, ip);
    }

    /** OTC "Link prescription" picker: signed lines with quantity left for the patients on this phone number. */
    @Transactional
    public List<RxLineResponse> searchByPhone(UUID tenantId, String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Enter at least 6 digits of the phone number.");
        }
        tenantContext.set(tenantId);
        return repo.searchByPhone(tenantId, digits).stream()
                .filter(r -> r.remainingQty().signum() > 0).toList();
    }

    /** CLN-024: what a line may dispense in all: the prescribed quantity once plus once per refill; null without a quantity. */
    static BigDecimal allowance(BigDecimal quantity, int refills) {
        return quantity == null ? null : quantity.multiply(BigDecimal.valueOf(1L + refills));
    }

    static BigDecimal remaining(BigDecimal quantity, BigDecimal dispensed) {
        if (quantity == null) return BigDecimal.ZERO;
        return quantity.subtract(dispensed == null ? BigDecimal.ZERO : dispensed).max(BigDecimal.ZERO);
    }

    private void block(UUID tenantId, UUID staffId, String ip, Line l, String type, String title, String detail) {
        recorder.record(tenantId, staffId, ip, l.chargeCode(), l.catalogueId(), type);
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, type, title, detail, Map.of("chargeCode", l.chargeCode()));
    }
}
