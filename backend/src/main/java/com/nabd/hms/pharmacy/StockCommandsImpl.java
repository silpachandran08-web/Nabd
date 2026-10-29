package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.FefoPicker.Batch;
import com.nabd.hms.pharmacy.api.StockCommands;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PHL-003 / PHL-005 / PHL-006: the single writer of stock_batches, pharmacy_stock_movements and the cached sum
 * charge_catalogue.stock_qty (see StockWriteOwnershipTest). The item row lock serialises every change of one item, then each
 * batch is changed by a guarded UPDATE and leaves one immutable movement row carrying the batch's own before/after.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class StockCommandsImpl implements StockCommands {

    private record Leg(UUID batchId, int delta) {
    }

    private final PharmacyRepository repo;
    private final BatchRepository batches;
    private final TenantContext tenant;
    private final ClinicClock clock;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;

    StockCommandsImpl(PharmacyRepository repo, BatchRepository batches, TenantContext tenant, ClinicClock clock, EventPublisher events,
                      AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.batches = batches;
        this.tenant = tenant;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
    }

    @Override
    public Optional<UUID> stockedItem(String chargeCode) {
        return repo.findStockedItemByCode(tenant.current(), chargeCode);
    }

    @Override
    public StockResult reserve(UUID itemId, int qty) {
        requirePositive(qty);
        int have = lock(itemId);
        planOrRefuse(itemId, qty, null, have);
        return result(itemId, have, have);
    }

    @Override
    public List<Pick> pick(UUID itemId, int qty, Integer courseDays) {
        requirePositive(qty);
        if (courseDays != null && courseDays < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Course days must be at least 1.");
        }
        return planOrRefuse(itemId, qty, courseDays, lock(itemId));
    }

    @Override
    public StockResult issue(UUID itemId, int qty, UUID invoiceId, String reason) {
        if (qty == 0) { // a zero-quantity line moves nothing
            int have = lock(itemId);
            return result(itemId, have, have);
        }
        requirePositive(qty);
        int have = lock(itemId);
        List<Leg> legs = planOrRefuse(itemId, qty, null, have).stream().map(p -> new Leg(p.batchId(), -p.qty())).toList();
        return apply(itemId, have, legs, "dispense", reason, null, invoiceId, null, null);
    }

    @Override
    public StockResult issueFromBatch(UUID itemId, UUID batchId, int qty, UUID invoiceId, UUID actorId, String overrideReason) {
        requirePositive(qty);
        int have = lock(itemId);
        Batch b = batches.list(tenant.current(), itemId, true).stream().filter(x -> x.id().equals(batchId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested batch was not found."));
        if (b.expiryDate() != null && b.expiryDate().isBefore(clock.today(tenant.current()))) {
            throw expired();
        }
        if (!"active".equals(b.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "batch-not-usable", "Batch not usable", "This batch is " + b.status() + ".");
        }
        if (overrideReason == null || overrideReason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "override-reason-required", "Reason required",
                    "Choosing a batch other than the first-expiry one needs a reason.");
        }
        if (b.qtyOnHand() < qty) {
            throw insufficient(b.qtyOnHand(), qty);
        }
        String why = overrideReason.trim();
        StockResult r = apply(itemId, have, List.of(new Leg(batchId, -qty)), "dispense", "FEFO override: " + why, actorId, invoiceId, why, null);
        ActorDirectory.Actor actor = actors.findActor(tenant.current(), actorId).orElseThrow(StockCommandsImpl::notFound);
        audit.record(tenant.current(), "staff", actorId, actor.name(), actor.role(), null, "pharmacy.fefo_overridden", "pharmacy_item", itemId,
                null, Map.of("batchId", batchId, "qty", qty, "reason", why));
        return r;
    }

    @Override
    public StockResult receive(UUID itemId, int qty, UUID actorId, String reason) {
        requirePositive(qty);
        if (inHouse()) {
            throw batchFieldsRequired();
        }
        int have = lock(itemId);
        return apply(itemId, have, List.of(new Leg(implicitBatch(itemId), qty)), "receipt", reason, actorId, null, null, null);
    }

    @Override
    public BatchStockResult receiveBatch(UUID itemId, BatchReceipt r, UUID actorId, String reason) {
        requirePositive(r.qty());
        int have = lock(itemId);
        UUID batchId;
        if (inHouse()) {
            if (r.batchNo() == null || r.batchNo().isBlank() || r.expiryDate() == null || r.mrp() == null || r.purchaseRate() == null) {
                throw batchFieldsRequired();
            }
            if (r.expiryDate().isBefore(clock.today(tenant.current()))) {
                throw expired();
            }
            UUID t = tenant.current();
            String no = r.batchNo().trim();
            batchId = batches.find(t, itemId, no, r.expiryDate()).map(Batch::id)
                    .orElseGet(() -> batches.insert(t, itemId, no, r.expiryDate(), r.mrp(), r.purchaseRate(), r.location(), r.supplierId(), r.grnLineId()));
        } else {
            batchId = implicitBatch(itemId); // Hybrid: one implicit batch, no batch fields
        }
        StockResult s = apply(itemId, have, List.of(new Leg(batchId, r.qty())), "receipt", reason, actorId, null, null,
                r.grnLineId() == null ? null : new String[]{"grn_line", r.grnLineId().toString()});
        return new BatchStockResult(s, batchId);
    }

    @Override
    public StockResult adjust(UUID itemId, int delta, UUID actorId, String reason) {
        requireNonZero(delta);
        int have = lock(itemId);
        if (delta > 0) {
            return apply(itemId, have, List.of(new Leg(implicitBatch(itemId), delta)), "adjustment", reason, actorId, null, null, null);
        }
        // shortcut: a stock-take reduction with several batches drains earliest expiry first (expired stock goes first); name a batch with adjustBatch to choose
        List<Leg> legs = new ArrayList<>();
        int left = -delta;
        for (Batch b : batches.list(tenant.current(), itemId, false)) {
            int take = Math.min(left, b.qtyOnHand());
            legs.add(new Leg(b.id(), -take));
            left -= take;
            if (left == 0) {
                break;
            }
        }
        if (left > 0) {
            throw insufficient(have, -delta);
        }
        return apply(itemId, have, legs, "adjustment", reason, actorId, null, null, null);
    }

    @Override
    public StockResult adjustBatch(UUID itemId, UUID batchId, int delta, UUID actorId, String reason) {
        requireNonZero(delta);
        int have = lock(itemId);
        batches.itemOf(tenant.current(), batchId).filter(itemId::equals).orElseThrow(StockCommandsImpl::notFound);
        return apply(itemId, have, List.of(new Leg(batchId, delta)), "adjustment", reason, actorId, null, null, null);
    }

    @Override
    public LockedBatch lockBatch(UUID batchId) {
        UUID itemId = batches.itemOf(tenant.current(), batchId).orElseThrow(StockCommandsImpl::notFound);
        lock(itemId);
        return new LockedBatch(itemId, batches.lock(tenant.current(), batchId).orElseThrow(StockCommandsImpl::notFound).qtyOnHand());
    }

    @Override
    public List<AdjustedBatch> adjustBatches(List<BatchAdjustment> adjustments, UUID actorId, UUID approverId, String sourceType, UUID sourceId) {
        if (adjustments.isEmpty()) {
            return List.of();
        }
        UUID t = tenant.current();
        int n = adjustments.size();
        UUID[] batchIds = new UUID[n];
        Integer[] deltas = new Integer[n];
        for (int i = 0; i < n; i++) {
            requireNonZero(adjustments.get(i).delta());
            batchIds[i] = adjustments.get(i).batchId();
            deltas[i] = adjustments.get(i).delta();
        }
        if (java.util.Arrays.stream(batchIds).anyMatch(java.util.Objects::isNull) || java.util.Arrays.stream(batchIds).distinct().count() != n) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Each batch may be adjusted once per request.");
        }
        Map<UUID, UUID> itemOf = batches.itemsOf(t, batchIds);
        if (itemOf.size() != n) {
            throw notFound();
        }
        UUID[] itemIds = itemOf.values().stream().distinct().sorted().toArray(UUID[]::new);
        Map<UUID, Integer> have = repo.lockItems(t, itemIds);
        if (have.size() != itemIds.length) {
            throw notFound();
        }
        Map<UUID, Integer> sums = batches.sumQtys(t, itemIds);
        have.forEach((item, qty) -> topUpOpening(item, qty - sums.getOrDefault(item, 0))); // rare: stock that reached stock_qty without a batch
        Map<UUID, Integer> after = batches.applyDeltas(t, batchIds, deltas);
        if (after.size() != n) {
            UUID short_ = java.util.Arrays.stream(batchIds).filter(b -> !after.containsKey(b)).findFirst().orElseThrow();
            throw new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Insufficient stock",
                    "A batch would go below zero; nothing was changed.", Map.of("batchId", short_));
        }
        Map<UUID, Integer> itemTotals = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            itemTotals.merge(itemOf.get(batchIds[i]), deltas[i], Integer::sum);
        }
        // the batches add up to the cache (just reconciled), so the guarded batch updates already proved no item goes negative
        if (repo.applyDeltas(t, itemTotals.keySet().toArray(UUID[]::new), itemTotals.values().toArray(Integer[]::new)) != itemTotals.size()) {
            throw new IllegalStateException("item stock cache out of step with its batches");
        }
        UUID[] items = new UUID[n];
        Integer[] before = new Integer[n];
        String[] reasons = new String[n];
        String[] codes = new String[n];
        List<AdjustedBatch> out = new ArrayList<>(n);
        List<Map.Entry<UUID, Map<String, ?>>> evs = new ArrayList<>(n);
        Map<UUID, Integer> running = new java.util.HashMap<>(have);
        for (int i = 0; i < n; i++) {
            BatchAdjustment a = adjustments.get(i);
            items[i] = itemOf.get(a.batchId());
            before[i] = after.get(a.batchId()) - a.delta();
            reasons[i] = a.reason();
            codes[i] = a.reasonCode();
            int onHand = running.merge(items[i], a.delta(), Integer::sum);
            out.add(new AdjustedBatch(a.batchId(), items[i], a.delta(), before[i], after.get(a.batchId())));
            evs.add(Map.entry(items[i], Map.of("itemId", items[i], "batchId", a.batchId(), "delta", a.delta(), "kind", "adjustment", "onHand", onHand)));
        }
        batches.insertAdjustments(t, items, batchIds, deltas, before, reasons, codes, actorId, approverId, sourceType, sourceId);
        events.publishAll(t, DomainEventType.PHARMACY_STOCK_BATCH_CHANGED, evs);
        return out;
    }

    @Override
    public StockResult returnIn(UUID itemId, int qty, UUID invoiceId, UUID actorId, String reason) {
        requirePositive(qty);
        int have = lock(itemId);
        List<Leg> legs = new ArrayList<>();
        int left = qty;
        // back into the batches this invoice took it from, then (a return of more than was dispensed) the implicit batch
        for (Object[] o : batches.outstandingForInvoice(tenant.current(), itemId, invoiceId)) {
            int put = Math.min(left, (Integer) o[1]);
            legs.add(new Leg((UUID) o[0], put));
            left -= put;
            if (left == 0) {
                break;
            }
        }
        if (left > 0) {
            legs.add(new Leg(implicitBatch(itemId), left));
        }
        return apply(itemId, have, legs, "restock", reason, actorId, invoiceId, null, null);
    }

    /** Locks the item (404 when unknown), then keeps the batches adding up to the cached sum: stock that reached
     * stock_qty without a batch (an item stocked before PHL-005, or by a test fixture) becomes the OPENING batch. */
    private int lock(UUID itemId) {
        UUID t = tenant.current();
        int have = repo.lockItem(t, itemId).orElseThrow(StockCommandsImpl::notFound).stockQty();
        topUpOpening(itemId, have - batches.sumQty(t, itemId));
        return have;
    }

    private void topUpOpening(UUID itemId, int missing) {
        if (missing > 0) {
            UUID t = tenant.current();
            UUID opening = openingBatch(itemId);
            int after = batches.applyDelta(t, opening, missing).orElseThrow();
            batches.insertMovement(t, itemId, opening, "opening", missing, after - missing, after, "Opening balance (PHL-005)", null, null, "migration", null, null);
        }
    }

    private UUID openingBatch(UUID itemId) {
        UUID t = tenant.current();
        return batches.find(t, itemId, BatchRepository.OPENING, null).map(Batch::id)
                .orElseGet(() -> batches.insert(t, itemId, BatchRepository.OPENING, null, null, null, null, null, null));
    }

    /** Hybrid keeps one implicit batch; for any other receipt without a named batch the earliest-expiry active batch is used. */
    private UUID implicitBatch(UUID itemId) {
        return batches.list(tenant.current(), itemId, true).stream().filter(b -> "active".equals(b.status())).findFirst()
                .map(Batch::id).orElseGet(() -> openingBatch(itemId));
    }

    private boolean inHouse() {
        return PharmacyMode.of(repo.findMode(tenant.current()).orElse("external")) == PharmacyMode.IN_HOUSE;
    }

    private List<Pick> planOrRefuse(UUID itemId, int qty, Integer courseDays, int have) {
        UUID t = tenant.current();
        LocalDate today = clock.today(t);
        List<Batch> all = batches.fefo(t, itemId);
        FefoPicker.Plan plan = FefoPicker.plan(all, qty, courseDays, today);
        if (plan.enough()) {
            return plan.picks();
        }
        boolean expiredLeft = all.stream().anyMatch(b -> "active".equals(b.status()) && b.expiryDate() != null && b.expiryDate().isBefore(today));
        if (expiredLeft && have >= qty) {
            throw expired();
        }
        throw insufficient(plan.sellable(), qty);
    }

    /** The one write: item cache (guarded), then each batch (guarded) with its movement row and event. */
    private StockResult apply(UUID itemId, int have, List<Leg> legs, String kind, String reason, UUID actorId, UUID invoiceId,
                              String overrideReason, String[] source) {
        UUID t = tenant.current();
        int total = legs.stream().mapToInt(Leg::delta).sum();
        int after = repo.applyDelta(t, itemId, total).orElseThrow(() -> insufficient(have, -total));
        int running = have;
        for (Leg l : legs) {
            int batchAfter = batches.applyDelta(t, l.batchId(), l.delta()).orElseThrow(() -> insufficient(0, -l.delta()));
            running += l.delta();
            batches.insertMovement(t, itemId, l.batchId(), kind, l.delta(), batchAfter - l.delta(), batchAfter, reason, actorId, invoiceId,
                    source == null ? null : source[0], source == null ? null : UUID.fromString(source[1]), overrideReason);
            events.publish(t, DomainEventType.PHARMACY_STOCK_BATCH_CHANGED, itemId,
                    Map.of("itemId", itemId, "batchId", l.batchId(), "delta", l.delta(), "kind", kind, "onHand", running));
        }
        return result(itemId, have, after);
    }

    private StockResult result(UUID itemId, int before, int after) {
        return new StockResult(itemId, before, after, repo.scheduleClassOf(tenant.current(), itemId).orElse(null));
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Quantity must be at least 1.");
        }
    }

    private static void requireNonZero(int delta) {
        if (delta == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-change", "Nothing to change", "The stock change is zero.");
        }
    }

    private static ApiException insufficient(int have, int want) {
        return new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Insufficient stock",
                "Only " + have + " in stock, not enough for " + want + " unit(s).");
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "batch-expired", "Batch expired", "Expired stock cannot be sold, dispensed or received.");
    }

    private static ApiException batchFieldsRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, "batch-details-required", "Batch details required",
                "Batch number, expiry date, MRP, purchase rate and quantity are required for an In-house pharmacy.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested pharmacy item was not found.");
    }
}
