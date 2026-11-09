package com.nabd.hms.pharmacy;

import com.nabd.hms.clinical.api.RxValidity;
import com.nabd.hms.clinical.api.SignedRxLines;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.dto.WorklistDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PHL-012: the In-house pharmacist's worklist. clinical.rx.signed.v1 creates one entry per prescription with its in-house lines
 * (idempotent on the prescription); external lines never enter it, and External or Hybrid clinics get none. Status: new -> in_progress
 * (claim, one winner) -> partially_dispensed -> dispensed as checkout bills the lines; open entries past the Rx validity of their
 * schedule class become expired and can no longer be claimed or dispensed (RxGate). Every change is a status_changed event (ids only),
 * which the sse-fanout group pushes on the pharmacy.worklist topic.
 */
@Service
@Transactional
public class PharmacyWorklistService implements RxValidity {

    private static final String NOT_FOUND = "The requested worklist entry was not found.";

    static final Set<String> STATUSES = Set.of("new", "in_progress", "partially_dispensed", "dispensed", "cancelled", "expired");

    private final PharmacyWorklistRepository repo;
    private final PharmacyRepository items;
    private final RxGateRepository rxLines;
    private final SignedRxLines rx;
    private final TenantContext tenant;
    private final ClinicClock clock;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;

    PharmacyWorklistService(PharmacyWorklistRepository repo, PharmacyRepository items, RxGateRepository rxLines, SignedRxLines rx, TenantContext tenant,
                    ClinicClock clock, EventPublisher events, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.items = items;
        this.rxLines = rxLines;
        this.rx = rx;
        this.tenant = tenant;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
    }

    /** The consumer of clinical.rx.signed.v1, in the event's tenant transaction. A redelivery finds the entry and does nothing. */
    public void onRxSigned(UUID prescriptionId, UUID queueEntryId) {
        UUID t = tenant.current();
        if (!PharmacyMode.IN_HOUSE.code().equals(items.findMode(t).orElse("external"))) {
            return; // External and Hybrid clinics keep the checkout path (PHL-018)
        }
        SignedRxLines.Signed signed = rx.signed(queueEntryId).filter(s -> s.prescriptionId().equals(prescriptionId)).orElse(null);
        if (signed == null) {
            return;
        }
        List<SignedRxLines.Line> lines = rx.forVisit(queueEntryId).stream().filter(l -> l.quantity() != null).toList(); // no quantity: nothing to dispense against (RxGate)
        if (lines.isEmpty()) {
            return;
        }
        List<PharmacyWorklistRepository.ItemRow> stocked = items(t, lines);
        List<UUID> lineIds = new ArrayList<>(), itemIds = new ArrayList<>();
        List<String> qtys = new ArrayList<>();
        for (SignedRxLines.Line l : lines) {
            PharmacyWorklistRepository.ItemRow hit = stocked.stream().filter(i -> l.drugId() != null && l.drugId().equals(i.drugId())).findFirst()
                    .orElseGet(() -> stocked.stream().filter(i -> norm(i.name()).equals(norm(l.drugName()))).findFirst().orElse(null));
            if (hit != null) {
                lineIds.add(l.prescriptionItemId());
                itemIds.add(hit.id());
                qtys.add(l.quantity().toPlainString());
            }
        }
        if (lineIds.isEmpty()) {
            return; // every line is bought outside
        }
        UUID[] ids = itemIds.toArray(UUID[]::new);
        Integer days = repo.validityDays(t, items.tenantMarket(t), ids);
        Instant validUntil = days == null ? null : signed.signedAt().plus(Duration.ofDays(days));
        repo.insert(t, prescriptionId, signed.patientId(), queueEntryId, signed.doctorId(), signed.signedAt(), lineIds.toArray(UUID[]::new), ids,
                        qtys.toArray(String[]::new), validUntil)
                .ifPresent(id -> events.publish(t, DomainEventType.PHARMACY_WORKLIST_ENTRY_CREATED, id,
                        Map.of("entryId", id, "prescriptionId", prescriptionId)));
    }

    /** PHL-013: the entry's valid_until when the prescription has one, else the same schedule-class rule applied to its lines now
     * (the entry is created asynchronously; a printout right after signing must carry the same date). */
    @Override
    public Instant validUntil(UUID prescriptionId, UUID queueEntryId) {
        UUID t = tenant.current();
        PharmacyWorklistRepository.EntryRow entry = repo.findByPrescription(t, prescriptionId).orElse(null);
        if (entry != null) {
            return entry.validUntil();
        }
        SignedRxLines.Signed signed = rx.signed(queueEntryId).filter(s -> s.prescriptionId().equals(prescriptionId)).orElse(null);
        List<SignedRxLines.Line> lines = signed == null ? List.of() : rx.forVisit(queueEntryId);
        UUID[] ids = lines.isEmpty() ? new UUID[0] : items(t, lines).stream().map(PharmacyWorklistRepository.ItemRow::id).toArray(UUID[]::new);
        Integer days = ids.length == 0 ? null : repo.validityDays(t, items.tenantMarket(t), ids);
        return days == null ? null : signed.signedAt().plus(Duration.ofDays(days));
    }

    private List<PharmacyWorklistRepository.ItemRow> items(UUID t, List<SignedRxLines.Line> lines) {
        String[] drugIds = lines.stream().map(SignedRxLines.Line::drugId).filter(java.util.Objects::nonNull).distinct().toArray(String[]::new);
        String[] names = lines.stream().map(l -> norm(l.drugName())).distinct().toArray(String[]::new);
        return repo.inHouseItems(t, drugIds, names);
    }

    /** @param status comma-separated filter; default the open statuses (new, in_progress, partially_dispensed) */
    public WorklistDtos.Page list(String status, String cursor, int limit) {
        String[] statuses = status == null || status.isBlank() ? PharmacyWorklistRepository.OPEN
                : Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toArray(String[]::new);
        for (String s : statuses) {
            if (!STATUSES.contains(s)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                        "Status must be one of new, in_progress, partially_dispensed, dispensed, cancelled, expired.");
            }
        }
        UUID t = tenant.current();
        List<PharmacyWorklistRepository.EntryRow> rows = repo.page(t, statuses, cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor), limit + 1);
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            PharmacyWorklistRepository.EntryRow last = rows.get(limit - 1);
            next = new Cursor(last.signedAt(), last.id()).encode(); // keyset on the sign time
        }
        return new WorklistDtos.Page(view(t, rows), next);
    }

    /** One claimer wins; the other gets 409 already-claimed naming them. Claiming your own entry again is a no-op. */
    public WorklistDtos.Entry claim(UUID id, UUID staffId, String ip) {
        UUID t = tenant.current();
        if (repo.claim(t, id, staffId)) {
            ActorDirectory.Actor actor = actors.findActor(t, staffId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
            audit.record(t, "staff", staffId, actor.name(), actor.role(), ip, "pharmacy.worklist_claimed", "pharmacy_worklist_entry", id,
                    Map.of("status", "new"), Map.of("status", "in_progress"));
            changed(t, id, "in_progress");
            return entry(t, id);
        }
        PharmacyWorklistRepository.EntryRow e = repo.find(t, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        if ("in_progress".equals(e.status()) && staffId.equals(e.claimedBy())) {
            return view(t, List.of(e)).get(0);
        }
        if ("expired".equals(e.status())) {
            throw expired();
        }
        if (e.claimedBy() != null) {
            String name = e.claimedByName() == null ? "another pharmacist" : e.claimedByName();
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("claimedBy", e.claimedBy());
            extras.put("claimedByName", name);
            throw new ApiException(HttpStatus.CONFLICT, "already-claimed", "Already taken", "Taken by " + name + ".", extras);
        }
        throw new ApiException(HttpStatus.CONFLICT, "not-claimable", "Cannot be started", "This prescription is " + e.status().replace('_', ' ') + ".");
    }

    /** Checkout billed these prescription lines (same transaction): the entries holding them move to partially_dispensed or dispensed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDispensed(Collection<UUID> prescriptionItemIds) {
        if (prescriptionItemIds.isEmpty()) {
            return;
        }
        UUID t = tenant.current();
        for (PharmacyWorklistRepository.EntryRow e : repo.lockOpenWithLines(t, prescriptionItemIds.toArray(UUID[]::new))) {
            List<PharmacyWorklistRepository.LineRow> lines = repo.lines(t, new UUID[]{e.id()});
            Map<UUID, BigDecimal> done = rxLines.dispensed(t, lines.stream().map(PharmacyWorklistRepository.LineRow::prescriptionItemId).toList());
            boolean all = lines.stream().allMatch(l -> done.getOrDefault(l.prescriptionItemId(), BigDecimal.ZERO).compareTo(l.qty()) >= 0);
            boolean any = lines.stream().anyMatch(l -> done.getOrDefault(l.prescriptionItemId(), BigDecimal.ZERO).signum() > 0);
            String to = all ? "dispensed" : any ? "partially_dispensed" : null;
            if (to != null && !to.equals(e.status())) {
                repo.setStatus(t, e.id(), to);
                changed(t, e.id(), to);
            }
        }
    }

    /** The expiry job's pass for the bound tenant; returns how many entries expired. */
    public int expireDue() {
        UUID t = tenant.current();
        List<UUID> ids = repo.expireDue(t);
        ids.forEach(id -> changed(t, id, "expired"));
        return ids.size();
    }

    private void changed(UUID t, UUID id, String status) {
        events.publish(t, DomainEventType.PHARMACY_WORKLIST_STATUS_CHANGED, id, Map.of("entryId", id, "status", status));
    }

    private WorklistDtos.Entry entry(UUID t, UUID id) {
        return view(t, List.of(repo.find(t, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND)))).get(0);
    }

    private List<WorklistDtos.Entry> view(UUID t, List<PharmacyWorklistRepository.EntryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<PharmacyWorklistRepository.LineRow> lines = repo.lines(t, rows.stream().map(PharmacyWorklistRepository.EntryRow::id).toArray(UUID[]::new));
        UUID[] itemIds = lines.stream().map(PharmacyWorklistRepository.LineRow::itemId).distinct().toArray(UUID[]::new);
        Map<UUID, String> names = repo.itemNames(t, itemIds);
        Map<UUID, Integer> usable = repo.usableStock(t, itemIds, clock.today(t));
        Map<UUID, BigDecimal> done = rxLines.dispensed(t, lines.stream().map(PharmacyWorklistRepository.LineRow::prescriptionItemId).toList());
        Map<UUID, List<WorklistDtos.Line>> byEntry = new LinkedHashMap<>();
        for (PharmacyWorklistRepository.LineRow l : lines) {
            byEntry.computeIfAbsent(l.entryId(), k -> new ArrayList<>()).add(new WorklistDtos.Line(l.prescriptionItemId(), names.get(l.itemId()),
                    l.qty(), done.getOrDefault(l.prescriptionItemId(), BigDecimal.ZERO), usable.getOrDefault(l.itemId(), 0) > 0));
        }
        return rows.stream().map(r -> new WorklistDtos.Entry(r.id(), r.prescriptionId(), r.queueEntryId(), r.token(), r.patientName(),
                r.doctorName(), r.signedAt(), r.status(), r.claimedBy(), r.claimedByName(), r.validUntil(),
                byEntry.getOrDefault(r.id(), List.of()))).toList();
    }

    static ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "rx-expired", "Prescription expired",
                "This prescription has passed its validity date and can no longer be dispensed.");
    }


    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
