package com.nabd.hms.pharmacy;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.clinical.api.DrugMasterLookup;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.Entitlements;
import com.nabd.hms.pharmacy.dto.DispensingQueueEntryResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemStatusRequest;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.dto.PharmacyCapabilitiesResponse;
import com.nabd.hms.pharmacy.dto.PharmacyScheduleClassResponse;
import com.nabd.hms.pharmacy.dto.PharmacyStockAdjustmentRequest;
import com.nabd.hms.pharmacy.dto.PharmacyStockMovementResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import com.nabd.hms.pharmacy.dto.PharmacySettingsResponse;
import com.nabd.hms.pharmacy.dto.PharmacySettingsWriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.pharmacy.PharmacyModels.DispensingQueueRow;
import static com.nabd.hms.pharmacy.PharmacyModels.PharmacyItemRow;

/**
 * Pharmacy items, settings and the dispensing queue. The mode gate is {@link RequiresPharmacyMode} on the controller
 * (PHL-004); stock changes go through {@link StockCommands} (PHL-003). In-house extras (batches, registers, FEFO) are
 * later stories.
 */
@Service
public class PharmacyService {

    private final PharmacyRepository repo;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    private final ActorDirectory actors;
    private final EventPublisher events;
    private final DrugMasterLookup drugs;
    private final StockCommands stock;
    private final PharmacySchedules schedules;
    private final PharmacyModes modes;
    private final Entitlements entitlements;
    private final PharmacyTaxInputsRepository taxInputs;
    private final com.nabd.hms.organization.api.RegionRules regions;

    PharmacyService(PharmacyRepository repo, TenantContext tenantContext, AuditService auditService, ActorDirectory actors,
                    EventPublisher events, DrugMasterLookup drugs, StockCommands stock, PharmacySchedules schedules,
                    PharmacyModes modes, Entitlements entitlements, PharmacyTaxInputsRepository taxInputs,
                    com.nabd.hms.organization.api.RegionRules regions) {
        this.taxInputs = taxInputs;
        this.regions = regions;
        this.stock = stock;
        this.schedules = schedules;
        this.modes = modes;
        this.entitlements = entitlements;
        this.drugs = drugs;
        this.events = events;
        this.actors = actors;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
    }

    @Transactional
    public PharmacySettingsResponse getSettings(UUID tenantId) {
        tenantContext.set(tenantId);
        return settingsView(tenantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private PharmacySettingsResponse settingsView(UUID tenantId) {
        return repo.findSettings(tenantId).map(r -> new PharmacySettingsResponse(r.mode(), r.modeChangedAt(), licences(r.licenceJson()),
                r.pharmacistInChargeStaffId(), r.alertRecipientStaffId()))
                .orElse(new PharmacySettingsResponse("external", null, Map.of(), null, null));
    }

    private static Map<String, String> licences(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** PHA-006 / PHL-004: a real mode change needs a reason, is audited, and (for In-house) needs the plan entitlement; re-sending
     * the current mode is a no-op for the mode, though the licence numbers and the two staff references can still be saved. */
    @Transactional
    public PharmacySettingsResponse updateSettings(UUID tenantId, UUID callerStaffId, String ipAddress,
                                                   PharmacySettingsWriteRequest req) {
        tenantContext.set(tenantId);
        PharmacySettingsResponse cur = settingsView(tenantId);
        String current = cur.mode();
        String target = req.mode() == null ? current : req.mode();
        if (java.util.Arrays.stream(PharmacyMode.values()).noneMatch(m -> m.code().equals(target))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-mode", "Invalid mode", "Mode must be external, hybrid or in_house.");
        }
        boolean modeChanged = !target.equals(current);
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (modeChanged) {
            if ("in_house".equals(target) && !entitlements.has("pharmacy_inhouse")) {
                String plan = entitlements.planUnlocking("pharmacy_inhouse").orElse(null);
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "entitlement-required", "Entitlement required",
                        "In-house pharmacy is not part of your plan." + (plan == null ? "" : " The " + plan + " plan includes it."),
                        plan == null ? Map.of("module", "pharmacy_inhouse") : Map.of("module", "pharmacy_inhouse", "plan", plan));
            }
            if (reason.length() < 3 || reason.length() > 200) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                        "Give a reason (3-200 characters) for changing the pharmacy mode.");
            }
        }
        UUID alertTo = req.alertRecipientStaffId() != null ? checkedStaff(tenantId, req.alertRecipientStaffId()) : cur.alertRecipientStaffId();
        UUID inCharge = req.pharmacistInChargeStaffId() != null ? checkedStaff(tenantId, req.pharmacistInChargeStaffId()) : cur.pharmacistInChargeStaffId();
        Map<String, String> licence = req.licenceNumbers() != null ? req.licenceNumbers() : cur.licenceNumbers();
        boolean extrasChanged = !java.util.Objects.equals(alertTo, cur.alertRecipientStaffId())
                || !java.util.Objects.equals(inCharge, cur.pharmacistInChargeStaffId()) || !licence.equals(cur.licenceNumbers());
        if (!modeChanged && !extrasChanged) {
            return cur;
        }
        try {
            repo.saveSettings(tenantId, target, modeChanged, callerStaffId, JSON.writeValueAsString(licence), inCharge, alertTo);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        modes.evict(tenantId);
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(this::notFound);
        if (modeChanged) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("mode", target);
            after.put("reason", reason);
            auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                    "pharmacy.mode_changed", "pharmacy_settings", tenantId, Map.of("mode", current), after);
        }
        if (extrasChanged) { // values (licence numbers) stay out of the audit text; the keys say what moved
            auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                    "pharmacy.settings_changed", "pharmacy_settings", tenantId, Map.of("licenceKeys", cur.licenceNumbers().keySet()),
                    Map.of("licenceKeys", licence.keySet()));
        }
        return settingsView(tenantId);
    }

    private UUID checkedStaff(UUID tenantId, UUID staffId) {
        actors.findActor(tenantId, staffId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "staff-not-found",
                "Staff member not found", "That staff member does not exist in this clinic."));
        return staffId;
    }

    /** PHL-004: what the clinic's mode switches on; the nav is built from this. */
    @Transactional
    public PharmacyCapabilitiesResponse capabilities() {
        UUID t = tenantContext.current();
        PharmacyMode m = PharmacyMode.of(repo.findMode(t).orElse("external"));
        return new PharmacyCapabilitiesResponse(m.code(), m.features);
    }

    /** PHL-004 read model for the Rx: names to print under "Buy outside" instead of "dispensed here". */
    @Transactional
    public List<String> buyOutside() {
        return repo.buyOutsideNames(tenantContext.current());
    }

    /** PHL-002: the classes valid for this clinic's market. */
    @Transactional
    public List<PharmacyScheduleClassResponse> scheduleClasses() {
        return schedules.view();
    }

    @Transactional
    public List<PharmacyItemResponse> listItems(UUID tenantId, String scheduleClass) {
        tenantContext.set(tenantId);
        return repo.listItems(tenantId, scheduleClass == null || scheduleClass.isBlank() ? null : scheduleClass).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PharmacyItemResponse addItem(UUID tenantId, UUID callerStaffId, String ipAddress, PharmacyItemWriteRequest req) {
        tenantContext.set(tenantId);
        String drugId = linkedDrugId(req);
        String cls = schedules.resolve(req.scheduleClass(), drugId, req.isRx(), null);
        String code = "PHM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // PHL-021: an item added without a tax rate takes the market's headline rate (AE 5, SA 15); markets without one (IN slabs, QA) stay 0
        BigDecimal rate = req.taxRatePercent() != null ? req.taxRatePercent()
                : java.util.Objects.requireNonNullElse(regions.region(repo.tenantMarket(tenantId)).standardTaxRate(), BigDecimal.ZERO);
        UUID id = repo.insertItem(tenantId, code, req.withTaxRatePercent(rate));
        repo.upsertProfile(tenantId, id, cls, req.genericName(), req.packSize(), req.unit(), req.inHouse());
        taxInputs.setInputs(tenantId, id, req.priceInclusiveOfTax(), req.zeroRated(), req.mrp());
        repo.setReturnFlags(tenantId, id, req.coldChain(), req.returnable());
        if (drugId != null) {
            repo.setDrug(tenantId, id, drugId);
        }
        catalogueChanged(tenantId, id, "added");
        int opening = req.stockQtyOrZero();
        if (opening > 0) {
            // Opening stock is a stock change like any other: it gets a movement row and an audit event.
            recordAdjustment(tenantId, callerStaffId, ipAddress, id, 0, opening, "other", "Opening stock");
        }
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    /** Edits name, price, tax, HSN, schedule class and the descriptive fields. A stockQty in the body is ignored on purpose (V2-004): stock only
     * changes through {@link #adjustStock}, so an edit from a stale list can no longer overwrite a newer count. A class change is audited. */
    @Transactional
    public PharmacyItemResponse updateItem(UUID tenantId, UUID callerStaffId, String ipAddress, UUID id, PharmacyItemWriteRequest req) {
        tenantContext.set(tenantId);
        PharmacyItemRow before = repo.lockItem(tenantId, id).orElseThrow(this::notFound);
        String drugId = linkedDrugId(req);
        String cls = schedules.resolve(req.scheduleClass(), drugId, req.isRx(), before.scheduleClass());
        repo.updateItem(tenantId, id, req);
        repo.upsertProfile(tenantId, id, cls, req.genericName(), req.packSize(), req.unit(), req.inHouse());
        taxInputs.setInputs(tenantId, id, req.priceInclusiveOfTax(), req.zeroRated(), req.mrp());
        repo.setReturnFlags(tenantId, id, req.coldChain(), req.returnable());
        if (drugId != null && !drugId.equals(before.drugId())) {
            repo.setDrug(tenantId, id, drugId);
        }
        if (!cls.equals(before.scheduleClass())) {
            audit(tenantId, callerStaffId, ipAddress, "pharmacy.item_schedule_changed", id,
                    Map.of("scheduleClass", before.scheduleClass()), Map.of("scheduleClass", cls, "itemCode", before.code()));
        }
        catalogueChanged(tenantId, id, "updated");
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    /** The drug master id to link, checked like PUT drug-link (exists, not retired); null when the request sends none. */
    private String linkedDrugId(PharmacyItemWriteRequest req) {
        if (req.drugMasterId() == null || req.drugMasterId().isBlank()) {
            return null;
        }
        return drugs.find(req.drugMasterId()).filter(DrugMasterLookup.DrugRef::active).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "DRUG_NOT_FOUND", "Drug not found", "No such drug in the drug master.")).id();
    }

    /** OPS-021: pharmacy items are catalogue rows; a price or active change must reach the cached price catalogue too. */
    private void catalogueChanged(UUID tenantId, UUID id, String status) {
        events.publish(tenantId, DomainEventType.CATALOGUE_CHANGED, id, Map.of("chargeHeadId", id, "status", status));
    }

    /** V2-004: relative (delta) or stock-take (setTo) change, reason mandatory. The item row is locked, the new
     * count is computed from the locked value, and an immutable movement row plus an audit event record it. */
    @Transactional
    public PharmacyItemResponse adjustStock(UUID tenantId, UUID callerStaffId, String ipAddress, UUID id,
                                             PharmacyStockAdjustmentRequest req) {
        tenantContext.set(tenantId);
        if ((req.delta() == null) == (req.setTo() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "Send exactly one of 'delta' (a change) or 'setTo' (a counted total).");
        }
        // PHL-010: the reason code comes from the fixed list. A caller that sends only the older free-text reason is recorded
        // as 'other' with that text, so every adjustment carries a code; 'other' always needs the text.
        String code = req.reasonCode() == null ? "other" : req.reasonCode();
        String text = req.reason() == null ? "" : req.reason().trim();
        if ("other".equals(code) && text.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                    req.reasonCode() == null ? "A reason is required for every stock change." : "Reason 'other' needs a note.");
        }
        String reason = req.reasonCode() == null ? text : StockTakeService.reasonText(code, text.isEmpty() ? null : text);
        PharmacyItemRow item = repo.lockItem(tenantId, id).orElseThrow(this::notFound);
        if (item.registerType() != null) { // PHL-010: a controlled drug changes per batch with a second signer, never here
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "approval-required", "Second sign-off required",
                    "Stock of a controlled drug is adjusted by batch, with a second sign-off.");
        }
        long target = req.setTo() != null ? req.setTo() : (long) item.stockQty() + req.delta();
        if (target < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Stock cannot go below zero",
                    "Only " + item.stockQty() + " in stock, so the count cannot be reduced by " + (-req.delta()) + ".");
        }
        if (target == item.stockQty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-change", "Nothing to change",
                    "The stock count is already " + item.stockQty() + ".");
        }
        recordAdjustment(tenantId, callerStaffId, ipAddress, id, item.stockQty(), (int) target, code, reason);
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    /** V2-007: activate / deactivate. Idempotent; only a real change is audited. */
    @Transactional
    public PharmacyItemResponse setActive(UUID tenantId, UUID callerStaffId, String ipAddress, UUID id,
                                           PharmacyItemStatusRequest req) {
        tenantContext.set(tenantId);
        PharmacyItemRow item = repo.lockItem(tenantId, id).orElseThrow(this::notFound);
        boolean active = Boolean.TRUE.equals(req.active());
        if (item.active() != active) {
            repo.setActive(tenantId, id, active);
            catalogueChanged(tenantId, id, "updated");
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("active", item.active());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("active", active);
            after.put("itemCode", item.code());
            after.put("reason", req.reason() == null || req.reason().isBlank() ? null : req.reason().trim());
            audit(tenantId, callerStaffId, ipAddress, active ? "pharmacy.item_activated" : "pharmacy.item_deactivated", id, before, after);
        }
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    @Transactional
    public List<PharmacyStockMovementResponse> movements(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        repo.findItem(tenantId, id).orElseThrow(this::notFound);
        return repo.listMovements(tenantId, id).stream()
                .map(m -> new PharmacyStockMovementResponse(m.id(), m.kind(), m.delta(), m.qtyBefore(), m.qtyAfter(), m.reason(),
                        m.actorId(), m.actorName(), m.occurredAt()))
                .toList();
    }

    private void recordAdjustment(UUID tenantId, UUID staffId, String ip, UUID itemId, int before, int after, String reasonCode, String reason) {
        stock.adjust(itemId, after - before, staffId, reasonCode, reason);
        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("stockQty", before);
        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("stockQty", after);
        afterState.put("delta", after - before);
        afterState.put("reason", reason);
        afterState.put("reasonCode", reasonCode);
        audit(tenantId, staffId, ip, "pharmacy.stock_adjusted", itemId, beforeState, afterState);
    }

    private void audit(UUID tenantId, UUID staffId, String ip, String action, UUID itemId, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, action, "pharmacy_item", itemId, before, after);
    }

    /** NB-179: live query, not a job — "within five seconds of consultation close" is trivially true
     * for a read that runs the instant the queue page loads. */
    @Transactional
    public List<DispensingQueueEntryResponse> dispensingQueue(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listDispensingQueue(tenantId).stream().map(r -> toDispensingResponse(tenantId, r)).toList();
    }

    private DispensingQueueEntryResponse toDispensingResponse(UUID tenantId, DispensingQueueRow r) {
        List<DispensingQueueEntryResponse.Item> items = repo.listDispensingItems(tenantId, r.prescriptionId()).stream()
                .map(i -> new DispensingQueueEntryResponse.Item(i.drugName(), i.dosage(), i.frequency(), i.duration(), i.instructions(), i.stockQty()))
                .toList();
        return new DispensingQueueEntryResponse(r.prescriptionId(), r.queueEntryId(), r.patientId(), r.patientName(),
                r.doctorName(), r.signedAt(), r.token(), items);
    }

    private PharmacyItemResponse toResponse(PharmacyItemRow r) {
        return new PharmacyItemResponse(r.id().toString(), r.code(), r.name(), r.isRx(), r.hsnCode(), r.price(),
                r.taxRatePercent(), r.stockQty(), r.active(), r.drugId(), r.scheduleClass(), r.registerType(), r.genericName(),
                r.packSize(), r.unit(), r.inHouse(), r.priceInclusiveOfTax(), r.zeroRated(), r.mrp(), r.coldChain(), r.returnable());
    }

    /** CLN-019: owner maps an in-house item to a drug master id. is_rx is only SUGGESTED (scheduled product => prescription-only);
     * the clinic's own flag and the price stay as they are. A product retired in the KB cannot be linked to a new item. */
    @Transactional
    public Map<String, Object> linkDrug(UUID tenantId, UUID callerStaffId, String ipAddress, UUID id, String drugId) {
        PharmacyItemRow item = repo.findItem(tenantId, id).orElseThrow(this::notFound);
        DrugMasterLookup.DrugRef drug = drugs.find(drugId).filter(DrugMasterLookup.DrugRef::active).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "DRUG_NOT_FOUND", "Drug not found", "No such drug in the drug master."));
        repo.setDrug(tenantId, id, drug.id());
        // PHL-002: the drug's schedule flag sets the class (and so is_rx) without manual input
        String cls = schedules.resolve(null, drug.id(), item.isRx(), item.scheduleClass());
        if (!cls.equals(item.scheduleClass())) {
            repo.upsertProfile(tenantId, id, cls, null, null, null, null);
            audit(tenantId, callerStaffId, ipAddress, "pharmacy.item_schedule_changed", id,
                    Map.of("scheduleClass", item.scheduleClass()), Map.of("scheduleClass", cls, "itemCode", item.code()));
        }
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress, "pharmacy.item_drug_linked",
                "pharmacy_item", id, item.drugId() == null ? null : Map.of("drugId", item.drugId()), Map.of("drugId", drug.id()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", id);
        out.put("drugId", drug.id());
        out.put("suggestedIsRx", drug.scheduled());
        out.put("scheduleClass", cls);
        return out;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested pharmacy item was not found.");
    }
}
