package com.nabd.hms.lab;

import com.nabd.hms.billing.api.VisitCharges;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.VisitReadGate;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.lab.LabOrderDtos.Cancelled;
import com.nabd.hms.lab.LabOrderDtos.ExternalSheetRequest;
import com.nabd.hms.lab.LabOrderDtos.Favourite;
import com.nabd.hms.lab.LabOrderDtos.Favourites;
import com.nabd.hms.lab.LabOrderDtos.ItemRequest;
import com.nabd.hms.lab.LabOrderDtos.Order;
import com.nabd.hms.lab.LabOrderDtos.OrderItem;
import com.nabd.hms.lab.LabOrderDtos.OrderList;
import com.nabd.hms.lab.LabOrderDtos.OrderPlaced;
import com.nabd.hms.lab.LabOrderDtos.OrderRequest;
import com.nabd.hms.lab.LabOrderRepository.FavouriteRow;
import com.nabd.hms.lab.LabOrderRepository.ItemRow;
import com.nabd.hms.lab.LabOrderRepository.OrderRow;
import com.nabd.hms.lab.LabRepository.PanelRow;
import com.nabd.hms.lab.LabRepository.PanelTestRow;
import com.nabd.hms.lab.LabRepository.TestRow;
import com.nabd.hms.organization.api.ChargeCatalogue;
import com.nabd.hms.organization.api.ChargeCatalogue.Charge;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.api.PatientSummaries;
import com.nabd.hms.queue.api.VisitQueries;
import com.nabd.hms.shared.notify.CancelReason;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PHL-043: lab orders from the consult. An order holds one row per test the lab performs (a panel is expanded into its tests);
 * what the patient pays for is one price-list head per test ordered on its own and one per panel, put on the visit's checkout
 * through billing.api.VisitCharges and taken off again when an uncollected order is cancelled. An order to a lab outside Nabd
 * (destination external) charges nothing: the outside lab bills the patient. The tenant is the one bound to the transaction.
 */
@Service
public class LabOrderService {

    /** The asking module's name on billing's pending charges. */
    static final String CHARGE_SOURCE = "lab";
    static final String NOTICE_SUBJECT = "lab_order";
    private static final int FAVOURITES = 8;
    private static final DateTimeFormatter SHEET_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private final LabOrderRepository orders;
    private final LabRepository catalogue;
    private final ChargeCatalogue charges;
    private final VisitCharges visitCharges;
    private final VisitQueries visits;
    private final VisitReadGate visitGate;
    private final PatientSummaries patients;
    private final PatientRefs patientRefs;
    private final NotificationPort notifications;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ClinicClock clock;

    LabOrderService(LabOrderRepository orders, LabRepository catalogue, ChargeCatalogue charges, VisitCharges visitCharges, VisitQueries visits,
                    VisitReadGate visitGate, PatientSummaries patients, PatientRefs patientRefs, NotificationPort notifications, TenantContext tenantContext,
                    ActorDirectory actors, AuditService audit, EventPublisher events, ClinicClock clock) {
        this.orders = orders;
        this.catalogue = catalogue;
        this.charges = charges;
        this.visitCharges = visitCharges;
        this.visits = visits;
        this.visitGate = visitGate;
        this.patients = patients;
        this.patientRefs = patientRefs;
        this.notifications = notifications;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** The order's printed reference and barcode value: LO- and the last 12 hex digits of its id. */
    public static String reference(UUID orderId) {
        return "LO-" + orderId.toString().replace("-", "").substring(20).toUpperCase(Locale.ROOT);
    }

    @Transactional
    public OrderPlaced place(Jwt jwt, UUID staffId, String ip, OrderRequest req) {
        UUID tenantId = tenantContext.current();
        PatientSummaries.Summary patient = patients.find(req.patientId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        String kind = req.destination().kind();
        if ("partner".equals(kind)) { // no partner-lab register exists yet (PHL-055), so the id cannot be checked against this clinic
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "partner-lab-unknown", "Unknown partner lab",
                    "Partner labs are not set up yet; order in-house or print an order sheet for an outside lab.");
        }
        UUID visitId = req.queueEntryId();
        VisitQueries.Visit visit = visitId == null ? null : visitOf(jwt, tenantId, visitId, patient.id());
        if (req.encounterId() != null && !req.encounterId().equals(visitId)) visitOf(jwt, tenantId, req.encounterId(), patient.id());
        boolean billable = visit != null && !"external".equals(kind);
        if (billable && "completed".equals(visit.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "visit-closed", "Visit closed",
                    "This visit is already billed and closed, so the tests cannot be added to its bill. Open a new visit to order them.");
        }

        // testId -> the panel it was ordered through (null = on its own), in the order the doctor picked
        Map<UUID, UUID> lines = new LinkedHashMap<>();
        Map<UUID, PanelRow> panels = new HashMap<>();
        boolean anyPanel = req.items().stream().anyMatch(i -> i.panelId() != null);
        if (anyPanel) catalogue.panels(tenantId).forEach(p -> panels.put(p.id(), p));
        List<PanelTestRow> links = anyPanel ? catalogue.panelTests(tenantId) : List.of();
        for (ItemRequest it : req.items()) {
            if ((it.testId() == null) == (it.panelId() == null)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Each item is one test or one panel.");
            }
            if (it.testId() != null) {
                add(lines, it.testId(), null);
                continue;
            }
            PanelRow p = panels.get(it.panelId());
            if (p == null || !p.active()) throw inactive();
            links.stream().filter(l -> l.panelId().equals(p.id())).forEach(l -> add(lines, l.testId(), p.id()));
        }
        Map<UUID, TestRow> tests = new HashMap<>();
        catalogue.testsByIds(tenantId, lines.keySet()).forEach(t -> tests.put(t.id(), t));
        if (lines.isEmpty() || tests.size() != lines.size() || tests.values().stream().anyMatch(t -> !t.active())) throw inactive();

        // one head per test ordered on its own and one per panel, in order
        Set<UUID> headIds = new LinkedHashSet<>();
        lines.forEach((testId, panelId) -> headIds.add(panelId == null ? tests.get(testId).chargeItemId() : panels.get(panelId).chargeItemId()));
        Map<UUID, Charge> heads = charges.find(headIds);
        if (headIds.stream().anyMatch(h -> heads.get(h) == null || !heads.get(h).active())) throw inactive();

        UUID orderId = orders.insertOrder(tenantId, patient.id(), req.encounterId(), visitId, staffId, visitId == null ? "walk_in" : "consult",
                req.priority(), kind, blankToNull(req.clinicalNote()), req.fastingHours());
        Map<UUID, UUID> chargeRef = new HashMap<>();
        if (billable) {
            visitCharges.add(tenantId, visitId, CHARGE_SOURCE, orderId, List.copyOf(headIds)).forEach(p -> chargeRef.put(p.chargeItemId(), p.id()));
        }
        List<ItemRow> rows = new ArrayList<>();
        Set<UUID> priced = new HashSet<>();
        lines.forEach((testId, panelId) -> {
            UUID head = panelId == null ? tests.get(testId).chargeItemId() : panels.get(panelId).chargeItemId();
            // a panel is one charge: its first test row carries the panel price, the others 0.00
            BigDecimal price = priced.add(head) ? heads.get(head).baseAmount() : BigDecimal.ZERO;
            rows.add(new ItemRow(null, orderId, testId, panelId, "ordered", price, chargeRef.get(head)));
        });
        orders.insertItems(tenantId, orderId, rows);

        events.publish(tenantId, DomainEventType.LAB_ORDER_PLACED, orderId, Map.of("orderId", orderId, "patientRef", patientRefs.refFor(patient.id()),
                "priority", req.priority(), "testCount", rows.size()));
        String notice = req.fastingHours() == null ? null : fastingNotice(orderId, patient, req.fastingHours());
        List<LabOrderDtos.Charge> charged = !billable ? List.of()
                : headIds.stream().map(heads::get).map(c -> new LabOrderDtos.Charge(c.code(), c.name(), c.baseAmount())).toList();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priority", req.priority());
        after.put("destination", kind);
        after.put("tests", rows.size());
        after.put("charges", charged.size());
        if (req.fastingHours() != null) {
            after.put("fastingHours", req.fastingHours());
            after.put("fastingNotice", notice);
        }
        record(tenantId, staffId, ip, "lab.order_placed", "lab_order", orderId, null, after);
        return new OrderPlaced(orderId, reference(orderId), charged, notice);
    }

    /** Idempotent: cancelling a cancelled order answers the same. 409 already-collected once any sample was collected. */
    @Transactional
    public Cancelled cancel(Jwt jwt, UUID staffId, String ip, UUID orderId, String reason) {
        UUID tenantId = tenantContext.current();
        OrderRow o = order(jwt, tenantId, orderId, true);
        if (!"cancelled".equals(o.status()) && !orders.cancel(tenantId, orderId, staffId, reason.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "already-collected", "Already collected",
                    "A sample was already collected for this order, so it cannot be cancelled here. Ask the lab.",
                    Map.of("code", "ALREADY_COLLECTED"));
        }
        VisitCharges.Removed removed = visitCharges.remove(tenantId, CHARGE_SOURCE, orderId);
        if (!"cancelled".equals(o.status())) {
            notifications.cancelBySubject(NOTICE_SUBJECT, orderId, CancelReason.SUBJECT_CHANGED); // a fasting notice not sent yet is stopped
            record(tenantId, staffId, ip, "lab.order_cancelled", "lab_order", orderId, Map.of("status", o.status()),
                    Map.of("status", "cancelled", "reason", reason.trim(), "chargesRemoved", removed.removed(), "chargesAlreadyBilled", removed.alreadyBilled()));
        }
        return new Cancelled(orderId, "cancelled", removed.removed(), removed.alreadyBilled());
    }

    /** The visit's orders, STAT first. IDC-001: a visit outside the caller's read scope answers 404 like an unknown one. */
    @Transactional
    public OrderList forVisit(Jwt jwt, UUID queueEntryId) {
        UUID tenantId = tenantContext.current();
        visitGate.requireVisit(jwt, queueEntryId);
        return new OrderList(views(tenantId, orders.byVisit(tenantId, queueEntryId)));
    }

    /** The tests and panels this doctor orders most, still active in the catalogue. Derived from the doctor's own orders: nothing to maintain. */
    @Transactional(readOnly = true)
    public Favourites favourites(UUID staffId) {
        UUID tenantId = tenantContext.current();
        List<FavouriteRow> rows = orders.favourites(tenantId, staffId, FAVOURITES);
        Map<UUID, TestRow> tests = new HashMap<>();
        catalogue.testsByIds(tenantId, rows.stream().map(FavouriteRow::testId).filter(java.util.Objects::nonNull).toList()).forEach(t -> tests.put(t.id(), t));
        Map<UUID, PanelRow> panels = new HashMap<>();
        if (rows.stream().anyMatch(r -> r.panelId() != null)) catalogue.panels(tenantId).forEach(p -> panels.put(p.id(), p));
        Set<UUID> headIds = new HashSet<>();
        tests.values().forEach(t -> headIds.add(t.chargeItemId()));
        panels.values().forEach(p -> headIds.add(p.chargeItemId()));
        Map<UUID, Charge> heads = charges.find(headIds);
        List<Favourite> out = new ArrayList<>();
        for (FavouriteRow r : rows) {
            if (r.panelId() != null) {
                PanelRow p = panels.get(r.panelId());
                if (p != null && p.active()) out.add(new Favourite("panel", p.id(), p.code(), p.name(), heads.get(p.chargeItemId()).baseAmount(), r.orders()));
            } else {
                TestRow t = tests.get(r.testId());
                if (t != null && t.active()) out.add(new Favourite("test", t.id(), t.code(), t.name(), heads.get(t.chargeItemId()).baseAmount(), r.orders()));
            }
        }
        return new Favourites(out);
    }

    /** The order as a printable sheet (A4 PDF) with the order reference as a Code 128 barcode. 409 order-cancelled for a cancelled order. */
    @Transactional
    public byte[] sheet(Jwt jwt, UUID staffId, String ip, UUID orderId) {
        UUID tenantId = tenantContext.current();
        OrderRow o = order(jwt, tenantId, orderId, false);
        if ("cancelled".equals(o.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "order-cancelled", "Order cancelled", "This order was cancelled, so there is no order sheet for it.");
        }
        Order view = views(tenantId, List.of(o)).get(0);
        PatientSummaries.Summary patient = patients.find(o.patientId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        List<String> tests = view.items().stream().map(i -> i.code() + "  " + i.name() + (i.panelName() == null ? "" : "  [" + i.panelName() + "]")).toList();
        record(tenantId, staffId, ip, "lab.order_sheet_printed", "lab_order", orderId, null, Map.of("destination", o.destinationKind(), "tests", tests.size()));
        return sheetPdf(tenantId, reference(orderId), null, patient, o.orderingDoctorId(), o.priority(), o.fastingHours(), o.clinicalNote(), tests, o.orderedAt());
    }

    /**
     * An order sheet for a lab outside Nabd with the tests as the doctor typed them: the path for a clinic without the lab module
     * (no catalogue, no lab order row, nothing charged). The barcode is a one-off reference, kept in the audit entry.
     */
    @Transactional
    public byte[] externalSheet(Jwt jwt, UUID staffId, String ip, UUID queueEntryId, ExternalSheetRequest req) {
        UUID tenantId = tenantContext.current();
        visitGate.requireVisit(jwt, queueEntryId);
        VisitQueries.Visit visit = visits.visit(tenantId, queueEntryId).orElseThrow(() -> ApiException.notFound("Visit not found."));
        PatientSummaries.Summary patient = patients.find(visit.patientId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        String reference = "LX-" + UUID.randomUUID().toString().replace("-", "").substring(20).toUpperCase(Locale.ROOT);
        List<String> tests = req.tests().stream().map(String::trim).toList();
        record(tenantId, staffId, ip, "lab.external_sheet_issued", "queue_entry", queueEntryId, null, Map.of("reference", reference, "tests", tests.size()));
        return sheetPdf(tenantId, reference, blankToNull(req.labName()), patient, staffId, req.priority() == null ? "routine" : req.priority(),
                req.fastingHours(), blankToNull(req.clinicalNote()), tests, clock.now());
    }

    // ---- helpers (each runs inside its caller's transaction) ----

    @Transactional(propagation = Propagation.MANDATORY)
    private byte[] sheetPdf(UUID tenantId, String reference, String labName, PatientSummaries.Summary patient, UUID doctorId, String priority,
                            Integer fastingHours, String note, List<String> tests, Instant at) {
        List<String> lines = new ArrayList<>();
        lines.add(orders.clinicName(tenantId));
        lines.add("LAB TEST ORDER");
        lines.add("");
        lines.add("Order reference: " + reference);
        lines.add("Date: " + SHEET_TIME.withZone(clock.zone(tenantId)).format(at));
        if (labName != null) lines.add("To: " + labName);
        lines.add("Patient: " + patient.name() + (patient.mrn() == null ? "" : "   MRN: " + patient.mrn()));
        lines.add("Ordered by: " + (doctorId == null ? "-" : actors.findActor(tenantId, doctorId).map(ActorDirectory.Actor::name).orElse("-")));
        lines.add("Priority: " + priority.toUpperCase(Locale.ROOT));
        if (fastingHours != null) lines.add("Fasting: " + fastingHours + " hours");
        if (note != null) lines.add("Clinical note: " + note);
        lines.add("");
        lines.add("Tests");
        for (int i = 0; i < tests.size(); i++) lines.add((i + 1) + ". " + tests.get(i));
        return LabPdf.render(LabPdf.A4, lines, reference);
    }

    /** The order, read-gated through its visit (IDC-001); 404 when unknown in this clinic. */
    @Transactional(propagation = Propagation.MANDATORY)
    private OrderRow order(Jwt jwt, UUID tenantId, UUID orderId, boolean forUpdate) {
        OrderRow o = orders.find(tenantId, orderId, forUpdate).orElseThrow(() -> ApiException.notFound("Lab order not found."));
        if (o.queueEntryId() != null) visitGate.requireVisit(jwt, o.queueEntryId());
        return o;
    }

    private VisitQueries.Visit visitOf(Jwt jwt, UUID tenantId, UUID visitId, UUID patientId) {
        visitGate.requireVisit(jwt, visitId);
        VisitQueries.Visit v = visits.visit(tenantId, visitId).orElseThrow(() -> ApiException.notFound("Visit not found."));
        if (!v.patientId().equals(patientId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "That visit belongs to another patient.");
        }
        return v;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private List<Order> views(UUID tenantId, List<OrderRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<ItemRow> items = orders.items(tenantId, rows.stream().map(OrderRow::id).toList());
        Map<UUID, TestRow> tests = new HashMap<>();
        catalogue.testsByIds(tenantId, items.stream().map(ItemRow::testId).distinct().toList()).forEach(t -> tests.put(t.id(), t));
        Map<UUID, String> panelNames = new HashMap<>();
        if (items.stream().anyMatch(i -> i.panelId() != null)) catalogue.panels(tenantId).forEach(p -> panelNames.put(p.id(), p.name()));
        return rows.stream().map(o -> {
            List<OrderItem> mine = items.stream().filter(i -> i.orderId().equals(o.id())).map(i -> new OrderItem(i.id(), i.testId(),
                    tests.get(i.testId()).code(), tests.get(i.testId()).name(), i.panelId(), i.panelId() == null ? null : panelNames.get(i.panelId()),
                    i.status(), i.price())).toList();
            return new Order(o.id(), reference(o.id()), o.patientId(), o.queueEntryId(), o.orderingDoctorId(), o.priority(), o.destinationKind(),
                    o.status(), o.clinicalNote(), o.fastingHours(), o.orderedAt(), mine.stream().map(OrderItem::price).reduce(BigDecimal.ZERO, BigDecimal::add),
                    "ordered".equals(o.status()), mine);
        }).toList();
    }

    /** At order time, in the order's transaction. A clinic without an approved template still gets its order: the answer says why nothing was sent. */
    private String fastingNotice(UUID orderId, PatientSummaries.Summary patient, int hours) {
        if (!notifications.sendable(NotificationPurpose.LAB_FASTING, null)) return "template_missing";
        return notifications.request(new NotificationRequest(NotificationPurpose.LAB_FASTING, NOTICE_SUBJECT, orderId, patient.id(), null, null,
                List.of(patient.name().trim().split("\\s+")[0], String.valueOf(hours)), null, null, null, "lab-fasting:" + orderId)).status();
    }

    private static void add(Map<UUID, UUID> lines, UUID testId, UUID panelId) {
        if (lines.containsKey(testId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "duplicate-test", "Test ordered twice",
                    "A test is in this order more than once (on its own or inside a panel). Remove the repeat and order again.",
                    Map.of("code", "DUPLICATE_TEST", "testId", testId.toString()));
        }
        lines.put(testId, panelId);
    }

    private static ApiException inactive() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "test-inactive", "Test not available",
                "A test or panel in this order is not active in the lab catalogue. Remove it and order again.", Map.of("code", "TEST_INACTIVE"));
    }

    private void record(UUID tenantId, UUID actorId, String ip, String action, String entity, UUID entityId, Object before, Object after) {
        String[] a = actors.findActor(tenantId, actorId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", actorId, a[0], a[1], ip, action, entity, entityId, before, after);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
