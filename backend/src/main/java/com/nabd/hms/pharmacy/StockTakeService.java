package com.nabd.hms.pharmacy;

import com.nabd.hms.auth.api.SecondSignOff;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.AdjustedBatch;
import com.nabd.hms.pharmacy.api.StockCommands.BatchAdjustment;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.AdjustmentView;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.Approver;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.BatchAdjustRequest;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.BatchAdjustResponse;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.CountEntry;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.CountsRequest;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.CountsResponse;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.Line;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.PostRequest;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.PostResponse;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.Sheet;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.StartRequest;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.StartResponse;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.Summary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PHL-010: stock take (freeze, count by shelf, variance, post all at once) and the reasoned batch adjustment. Any change of a
 * controlled batch (a class with a statutory register) needs a second signer: another staff member holding
 * pharmacy:adjust_approve who confirms with their own PIN. Both names are on the immutable movement rows and the session.
 */
@Service
public class StockTakeService {

    static final String APPROVE = "pharmacy:adjust_approve";
    private static final Map<String, String> REASON_LABELS = Map.of("damaged", "Damaged", "count_correction", "Count correction",
            "theft_loss", "Theft/loss", "expired", "Expired", "other", "Other");

    private final StockTakeRepository repo;
    private final StockCommands stock;
    private final SecondSignOff signOff;
    private final PharmacySchedules schedules;
    private final TenantContext tenant;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;

    StockTakeService(StockTakeRepository repo, StockCommands stock, SecondSignOff signOff, PharmacySchedules schedules, TenantContext tenant,
                     EventPublisher events, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.stock = stock;
        this.signOff = signOff;
        this.schedules = schedules;
        this.tenant = tenant;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
    }

    /** Idempotent: while a session with the same scope is open it is returned; a different open session is 409 stock-take-open. */
    @Transactional
    public StartResponse start(UUID staffId, String ip, StartRequest req) {
        UUID t = tenant.current();
        String value = switch (req.scope()) {
            case "location" -> required(req.location(), "location", "A location is required for a stock take by location.");
            case "class" -> {
                String cls = required(req.scheduleClass(), "scheduleClass", "A schedule class is required for a stock take by class.");
                if (!"OTC".equals(cls) && schedules.rules().stream().noneMatch(r -> r.code().equals(cls))) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schedule-not-valid-for-market", "Schedule not valid for this market",
                            "That schedule class is not valid for this clinic's market.");
                }
                yield cls;
            }
            default -> null;
        };
        var open = repo.findOpen(t);
        if (open.isPresent()) {
            if (open.get().scope().equals(req.scope()) && Objects.equals(open.get().scopeValue(), value)) {
                return new StartResponse(open.get().id(), repo.summary(t, open.get().id()).orElseThrow().lineCount());
            }
            throw openElsewhere(open.get().id());
        }
        UUID id;
        try {
            id = repo.insert(t, req.scope(), value, staffId);
        } catch (DuplicateKeyException e) { // a concurrent start won the one-open-session index
            throw openElsewhere(repo.findOpen(t).map(StockTakeRepository.Session::id).orElse(null));
        }
        int lines = repo.snapshot(t, id, req.scope(), value);
        if (lines == 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "nothing-to-count", "Nothing to count", "No batch with stock matches this scope.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("scope", req.scope());
        after.put("scopeValue", value);
        after.put("lineCount", lines);
        record(t, staffId, ip, "pharmacy.stock_take_started", id, after);
        return new StartResponse(id, lines);
    }

    @Transactional
    public List<Summary> list() {
        return repo.recent(tenant.current(), 20);
    }

    @Transactional
    public Sheet sheet(UUID id) {
        UUID t = tenant.current();
        Summary s = repo.summary(t, id).orElseThrow(StockTakeService::notFound);
        return new Sheet(s, repo.lines(t, id).stream().map(StockTakeRepository.LineRow::line).toList());
    }

    @Transactional
    public CountsResponse counts(UUID staffId, UUID id, CountsRequest req) {
        UUID t = tenant.current();
        requireOpen(repo.lock(t, id).orElseThrow(StockTakeService::notFound));
        int n = req.counts().size();
        UUID[] batchIds = new UUID[n];
        Integer[] counted = new Integer[n];
        String[] codes = new String[n];
        String[] notes = new String[n];
        for (int i = 0; i < n; i++) {
            CountEntry c = req.counts().get(i);
            batchIds[i] = c.batchId();
            counted[i] = c.counted();
            codes[i] = c.reasonCode();
            notes[i] = c.note() == null || c.note().isBlank() ? null : c.note().trim();
            if ("other".equals(codes[i]) && notes[i] == null) {
                throw noteRequired();
            }
        }
        if (java.util.Arrays.stream(batchIds).distinct().count() != n) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Each batch may appear once per request.");
        }
        if (repo.saveCounts(t, id, staffId, batchIds, counted, codes, notes) != n) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "batch-not-in-stock-take", "Batch not on this count sheet",
                    "One of the batches is not part of this stock take; nothing was saved.");
        }
        return new CountsResponse(n);
    }

    /** Idempotent: posting a posted session returns what it wrote. The session row lock serialises posting with count entry. */
    @Transactional
    public PostResponse post(UUID staffId, String ip, UUID id, PostRequest req) {
        UUID t = tenant.current();
        StockTakeRepository.Session s = repo.lock(t, id).orElseThrow(StockTakeService::notFound);
        if ("posted".equals(s.status())) {
            Summary sum = repo.summary(t, id).orElseThrow();
            List<AdjustmentView> done = repo.postedAdjustments(t, id);
            BigDecimal value = value(repo.lines(t, id), l -> l.postedDelta());
            return new PostResponse(id, "posted", done, value, sum.postedByName(), sum.approvedByName());
        }
        requireOpen(s);
        List<StockTakeRepository.LineRow> lines = repo.lines(t, id);
        List<Line> variant = lines.stream().map(StockTakeRepository.LineRow::line)
                .filter(l -> l.variance() != null && l.variance() != 0).toList();
        SecondSignOff.Signer approver = variant.stream().anyMatch(Line::controlled)
                ? approve(staffId, req == null ? null : req.approverStaffId(), req == null ? null : req.approverPin()) : null;
        UUID approverId = approver == null ? null : approver.staffId();
        List<AdjustedBatch> written = stock.adjustBatches(variant.stream().map(l -> {
            String code = l.reasonCode() == null ? "count_correction" : l.reasonCode();
            return new BatchAdjustment(l.batchId(), l.variance(), code, "Stock take: " + reasonText(code, l.note()));
        }).toList(), staffId, approverId, "stock_take", id);
        repo.markPosted(t, id, variant.stream().map(Line::batchId).toArray(UUID[]::new), variant.stream().map(Line::variance).toArray(Integer[]::new));
        repo.close(t, id, "posted", staffId, approverId);
        BigDecimal value = value(lines, Line::variance);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stockTakeId", id);
        data.put("variances", written.size());
        data.put("value", value);
        events.publish(t, DomainEventType.PHARMACY_STOCK_TAKE_POSTED, id, data);
        String makerName = actors.findActor(t, staffId).map(ActorDirectory.Actor::name).orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("variances", written.size());
        after.put("controlledVariances", variant.stream().filter(Line::controlled).count());
        after.put("value", value);
        after.put("makerName", makerName);
        after.put("approverStaffId", approverId);
        after.put("approverName", approver == null ? null : approver.name());
        record(t, staffId, ip, "pharmacy.stock_take_posted", id, after);
        return new PostResponse(id, "posted", written.stream().map(a -> new AdjustmentView(a.batchId(), a.itemId(), a.delta(), a.qtyBefore(),
                a.qtyAfter(), variant.stream().filter(l -> l.batchId().equals(a.batchId())).findFirst()
                        .map(l -> l.reasonCode() == null ? "count_correction" : l.reasonCode()).orElseThrow())).toList(),
                value, makerName, approver == null ? null : approver.name());
    }

    @Transactional
    public Summary cancel(UUID staffId, String ip, UUID id) {
        UUID t = tenant.current();
        StockTakeRepository.Session s = repo.lock(t, id).orElseThrow(StockTakeService::notFound);
        if (!"cancelled".equals(s.status())) {
            requireOpen(s);
            repo.close(t, id, "cancelled", null, null);
            record(t, staffId, ip, "pharmacy.stock_take_cancelled", id, Map.of("status", "cancelled"));
        }
        return repo.summary(t, id).orElseThrow();
    }

    /** One batch, delta or counted total, reason from the fixed list; a controlled batch needs the second signer. */
    @Transactional
    public BatchAdjustResponse adjust(UUID staffId, String ip, UUID batchId, BatchAdjustRequest req) {
        UUID t = tenant.current();
        if ((req.delta() == null) == (req.setTo() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "Send exactly one of 'delta' (a change) or 'setTo' (the counted total of the batch).");
        }
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if ("other".equals(req.reasonCode()) && note == null) {
            throw noteRequired();
        }
        // the PIN check runs first and in its own transaction: a wrong PIN is counted although this one rolls back, and no stock lock is held meanwhile
        SecondSignOff.Signer approver = repo.controlledBatch(t, batchId).orElseThrow(StockTakeService::batchNotFound)
                ? approve(staffId, req.approverStaffId(), req.approverPin()) : null;
        StockCommands.LockedBatch b = stock.lockBatch(batchId);
        int delta = req.delta() != null ? req.delta() : req.setTo() - b.qtyOnHand();
        if (delta == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-change", "Nothing to change", "The batch already holds " + b.qtyOnHand() + ".");
        }
        if (b.qtyOnHand() + delta < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Insufficient stock",
                    "Only " + b.qtyOnHand() + " in this batch, so it cannot go down by " + (-delta) + ".");
        }
        UUID approverId = approver == null ? null : approver.staffId();
        AdjustedBatch a = stock.adjustBatches(List.of(new BatchAdjustment(batchId, delta, req.reasonCode(), reasonText(req.reasonCode(), note))),
                staffId, approverId, null, null).get(0);
        ActorDirectory.Actor maker = actors.findActor(t, staffId).orElseThrow(StockTakeService::notFound);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("qtyOnHand", a.qtyBefore());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("itemId", a.itemId());
        after.put("qtyOnHand", a.qtyAfter());
        after.put("delta", delta);
        after.put("reasonCode", req.reasonCode());
        after.put("note", note);
        after.put("approverStaffId", approverId);
        after.put("approverName", approver == null ? null : approver.name());
        audit.record(t, "staff", staffId, maker.name(), maker.role(), ip, "pharmacy.stock_adjusted", "stock_batch", batchId, before, after);
        return new BatchAdjustResponse(batchId, a.itemId(), delta, a.qtyBefore(), a.qtyAfter(), req.reasonCode(), maker.name(),
                approver == null ? null : approver.name());
    }

    @Transactional
    public List<Approver> approvers(UUID staffId) {
        return signOff.holders(APPROVE, staffId).stream().map(s -> new Approver(s.staffId(), s.name())).toList();
    }

    /** Maker-checker: another person, holding pharmacy:adjust_approve, confirming with their own PIN. */
    private SecondSignOff.Signer approve(UUID makerId, UUID approverId, String pin) {
        if (approverId == null || pin == null || pin.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "approval-required", "Second sign-off required",
                    "A controlled drug is changed: a second staff member with approval rights must confirm with their PIN.");
        }
        if (approverId.equals(makerId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "approval-required", "Second sign-off required",
                    "The approver must be a different person from the one making the change.");
        }
        SecondSignOff.Confirmation c = signOff.confirm(approverId, pin, APPROVE);
        return switch (c.outcome()) {
            case CONFIRMED -> c.signer();
            case NOT_PERMITTED -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "approval-required", "Second sign-off required",
                    "That staff member cannot approve stock adjustments of controlled drugs.");
            case WRONG_PIN -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "approver-pin-invalid", "Approver PIN not accepted",
                    "The approver's PIN was not accepted. Nothing was changed.");
        };
    }

    private static BigDecimal value(List<StockTakeRepository.LineRow> lines, java.util.function.Function<Line, Integer> delta) {
        return lines.stream().filter(l -> delta.apply(l.line()) != null && l.purchaseRate() != null)
                .map(l -> l.purchaseRate().multiply(BigDecimal.valueOf(delta.apply(l.line()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    static String reasonText(String code, String note) {
        return REASON_LABELS.get(code) + (note == null ? "" : ": " + note);
    }

    private void record(UUID t, UUID staffId, String ip, String action, UUID id, Map<String, Object> after) {
        ActorDirectory.Actor actor = actors.findActor(t, staffId).orElseThrow(StockTakeService::notFound);
        audit.record(t, "staff", staffId, actor.name(), actor.role(), ip, action, "stock_take", id, null, after);
    }

    private static String required(String v, String field, String detail) {
        if (v == null || v.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail, Map.of("field", field));
        }
        return v.trim();
    }

    private static void requireOpen(StockTakeRepository.Session s) {
        if (!"open".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "stock-take-closed", "Stock take closed", "This stock take is " + s.status() + ".");
        }
    }

    private static ApiException openElsewhere(UUID openId) {
        return new ApiException(HttpStatus.CONFLICT, "stock-take-open", "Another stock take is open",
                "Post or cancel the open stock take first.", openId == null ? Map.of() : Map.of("stockTakeId", openId));
    }

    private static ApiException noteRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Reason 'other' needs a note.",
                Map.of("field", "note"));
    }

    private static ApiException batchNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested batch was not found.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested stock take was not found.");
    }
}
