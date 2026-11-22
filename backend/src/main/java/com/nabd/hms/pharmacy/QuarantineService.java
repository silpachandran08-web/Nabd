package com.nabd.hms.pharmacy;

import com.nabd.hms.auth.api.SecondSignOff;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.SeriesAllocator;
import com.nabd.hms.pharmacy.QuarantineRepository.BatchRow;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchAdjustment;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.Bin;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.DocumentLine;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.LineRequest;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.QuarantineRequest;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.QuarantinedBatch;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.SupplierReturn;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.SupplierReturnRequest;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.Valuation;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.WriteOff;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.WriteOffRequest;
import com.nabd.hms.shared.UuidV7;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-009: expired and damaged stock goes to quarantine (by hand, or by the nightly pass on the day after its expiry date,
 * the same "expired" rule FEFO uses), then leaves as a supplier return (a return note with a gapless debit-note number) or
 * a write-off with a record. Stock moves only through {@link StockCommands} as immutable 'return_out' / 'write_off'
 * movements. Writing off a controlled class (one with a statutory register) needs a witness: another staff member holding
 * pharmacy:adjust_approve who confirms with their own PIN (the PHL-010 second sign-off); both are on the movement row.
 */
@Service
public class QuarantineService {

    private static final String BATCH_NOT_FOUND = "The requested batch was not found.";

    private final QuarantineRepository repo;
    private final StockCommands stock;
    private final SecondSignOff signOff;
    private final SeriesAllocator series;
    private final TenantContext tenant;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;

    QuarantineService(QuarantineRepository repo, StockCommands stock, SecondSignOff signOff, SeriesAllocator series, TenantContext tenant,
                      ClinicClock clock, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.stock = stock;
        this.signOff = signOff;
        this.series = series;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
    }

    @Transactional
    public QuarantinedBatch quarantine(UUID staffId, String ip, UUID batchId, QuarantineRequest req) {
        UUID t = tenant.current();
        String note = blankToNull(req.note());
        if ("other".equals(req.reason()) && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Reason 'other' needs a note.", Map.of("field", "note"));
        }
        stock.quarantine(batchId, req.reason());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "quarantined");
        after.put("reason", req.reason());
        after.put("note", note);
        record(t, staffId, ip, "pharmacy.batch_quarantined", "stock_batch", batchId, Map.of("status", "active"), after);
        return view(repo.byIds(t, new UUID[]{batchId}).get(0));
    }

    /** The nightly pass for the bound clinic (runs inside the job's TenantScope transaction); returns how many batches it quarantined. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int quarantineExpired(UUID tenantId) {
        List<UUID> ids = stock.quarantineExpired();
        for (UUID id : ids) {
            audit.record(tenantId, "system", null, "Nightly quarantine", "system", null, "pharmacy.batch_quarantined", "stock_batch", id,
                    Map.of("status", "active"), Map.of("status", "quarantined", "reason", "expired"));
        }
        return ids.size();
    }

    @Transactional(readOnly = true)
    public Bin bin() {
        List<QuarantinedBatch> items = repo.quarantined(tenant.current()).stream().map(QuarantineService::view).toList();
        return new Bin(items, items.stream().map(QuarantinedBatch::value).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Transactional(readOnly = true)
    public Valuation valuation() {
        UUID t = tenant.current();
        return repo.valuation(t, clock.today(t));
    }

    /** One return note for one supplier. The debit-note number is taken last, so a refused line consumes none. */
    @Transactional
    public SupplierReturn supplierReturn(UUID staffId, String ip, SupplierReturnRequest req) {
        UUID t = tenant.current();
        List<BatchRow> rows = rows(t, req.lines());
        rows.stream().filter(r -> r.supplierId() != null && !r.supplierId().equals(req.supplierId())).findFirst().ifPresent(r -> {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "batch-not-from-supplier", "Batch is from another supplier",
                    "A batch on this return was received from a different supplier; nothing was changed.", Map.of("batchId", r.batchId()));
        });
        String supplier = req.supplierName().trim();
        UUID id = UuidV7.next();
        stock.removeQuarantined(removals(req.lines(), "Returned to supplier: " + supplier), "return_out", staffId, null, "supplier_return", id);
        List<DocumentLine> lines = lines(req.lines(), rows, false);
        BigDecimal value = total(lines);
        String number = series.allocate(SeriesAllocator.Kind.DEBIT_NOTE).formatted(); // gapless: taken last, rolled back with this transaction
        repo.insertReturn(t, id, req.supplierId(), supplier, number, value, blankToNull(req.reason()), staffId);
        repo.insertLines(t, "supplier_return_lines", id, lines);
        record(t, staffId, ip, "pharmacy.supplier_return_posted", "supplier_return", id, null,
                Map.of("debitNoteNumber", number, "lineCount", lines.size(), "value", value));
        return getReturn(id);
    }

    @Transactional(readOnly = true)
    public SupplierReturn getReturn(UUID id) {
        UUID t = tenant.current();
        return repo.findReturn(t, id, staff -> actors.findActor(t, staff).map(ActorDirectory.Actor::name).orElse(null))
                .orElseThrow(() -> ApiException.notFound("The requested supplier return was not found."));
    }

    @Transactional
    public WriteOff writeOff(UUID staffId, String ip, WriteOffRequest req) {
        UUID t = tenant.current();
        List<BatchRow> rows = rows(t, req.lines());
        // the PIN check runs first and in its own transaction: a wrong PIN is counted although this one rolls back, and no stock lock is held meanwhile
        SecondSignOff.Signer witness = rows.stream().anyMatch(r -> r.registerType() != null)
                ? witness(staffId, req.witnessStaffId(), req.witnessPin()) : null;
        UUID witnessId = witness == null ? null : witness.staffId();
        String note = blankToNull(req.note());
        UUID id = UuidV7.next();
        stock.removeQuarantined(removals(req.lines(), "Write-off: " + req.method() + (note == null ? "" : ": " + note)), "write_off", staffId,
                witnessId, "stock_write_off", id);
        List<DocumentLine> lines = lines(req.lines(), rows, true);
        BigDecimal value = total(lines);
        repo.insertWriteOff(t, id, req.method(), note, value, witnessId, staffId);
        repo.insertLines(t, "stock_write_off_lines", id, lines);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("method", req.method());
        after.put("note", note);
        after.put("lineCount", lines.size());
        after.put("controlledLines", lines.stream().filter(l -> l.registerType() != null).count());
        after.put("value", value);
        after.put("witnessStaffId", witnessId);
        after.put("witnessName", witness == null ? null : witness.name());
        String maker = record(t, staffId, ip, "pharmacy.stock_written_off", "stock_write_off", id, null, after);
        return new WriteOff(id, req.method(), value, maker, witness == null ? null : witness.name(), lines);
    }

    /** The second staff member of a controlled-class write-off: not the maker, holds pharmacy:adjust_approve, own PIN. */
    private SecondSignOff.Signer witness(UUID makerId, UUID witnessId, String pin) {
        if (witnessId == null || pin == null || pin.isBlank() || witnessId.equals(makerId)) {
            throw witnessRequired(witnessId != null && witnessId.equals(makerId)
                    ? "The witness must be a different person from the one writing the stock off."
                    : "A controlled drug is written off: a second staff member with approval rights must confirm with their PIN.");
        }
        SecondSignOff.Confirmation c = signOff.confirm(witnessId, pin, StockTakeService.APPROVE);
        return switch (c.outcome()) {
            case CONFIRMED -> c.signer();
            case NOT_PERMITTED -> throw witnessRequired("That staff member cannot witness the write-off of a controlled drug.");
            case WRONG_PIN -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "witness-pin-invalid", "Witness PIN not accepted",
                    "The witness's PIN was not accepted. Nothing was changed.");
        };
    }

    /** The batches behind the request lines, each batch once; 404 when one is not this clinic's. */
    @Transactional(propagation = Propagation.MANDATORY)
    private List<BatchRow> rows(UUID t, List<LineRequest> lines) {
        UUID[] ids = lines.stream().map(LineRequest::batchId).distinct().toArray(UUID[]::new);
        if (ids.length != lines.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Each batch may appear once per request.");
        }
        List<BatchRow> rows = repo.byIds(t, ids);
        if (rows.size() != ids.length) {
            throw ApiException.notFound(BATCH_NOT_FOUND);
        }
        return rows;
    }

    private static List<BatchAdjustment> removals(List<LineRequest> lines, String reason) {
        return lines.stream().map(l -> new BatchAdjustment(l.batchId(), -l.qty(), null, reason)).toList();
    }

    /** Request order; value = qty x the batch's purchase rate, half-up to 2 places (a batch without a rate counts 0). */
    private static List<DocumentLine> lines(List<LineRequest> req, List<BatchRow> rows, boolean withRegister) {
        List<DocumentLine> out = new ArrayList<>();
        for (LineRequest l : req) {
            BatchRow b = rows.stream().filter(r -> r.batchId().equals(l.batchId())).findFirst().orElseThrow();
            BigDecimal rate = b.purchaseRate() == null ? BigDecimal.ZERO : b.purchaseRate();
            out.add(new DocumentLine(b.batchId(), b.itemId(), b.itemName(), b.batchNo(), b.expiryDate(), l.qty(), rate,
                    value(rate, l.qty()), withRegister ? b.registerType() : null));
        }
        return out;
    }

    private static BigDecimal total(List<DocumentLine> lines) {
        return lines.stream().map(DocumentLine::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal value(BigDecimal rate, int qty) {
        return (rate == null ? BigDecimal.ZERO : rate).multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
    }

    private static QuarantinedBatch view(BatchRow b) {
        return new QuarantinedBatch(b.batchId(), b.itemId(), b.itemName(), b.batchNo(), b.expiryDate(), b.qtyOnHand(), b.purchaseRate(),
                value(b.purchaseRate(), b.qtyOnHand()), b.supplierId(), b.registerType() != null, b.status());
    }

    /** @return the acting staff member's name */
    private String record(UUID t, UUID staffId, String ip, String action, String entity, UUID id, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(t, staffId).orElseThrow(() -> ApiException.notFound(BATCH_NOT_FOUND));
        audit.record(t, "staff", staffId, actor.name(), actor.role(), ip, action, entity, id, before, after);
        return actor.name();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException witnessRequired(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "witness-required", "Witness required", detail);
    }
}
