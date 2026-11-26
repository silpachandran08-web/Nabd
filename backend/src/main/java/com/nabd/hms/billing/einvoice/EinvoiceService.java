package com.nabd.hms.billing.einvoice;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.shared.integration.ExternalCallGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-011: B2B e-invoices through the IRP, behind a per-clinic switch that is off by default.
 * <ul>
 * <li>Checkout calls {@link #onIssued} in its own transaction once an invoice is final. With the switch off, or for an invoice
 *     without a buyer GSTIN (B2C) or a bill of supply, nothing is written and nothing is ever sent.</li>
 * <li>Otherwise one einvoice_documents row waits in 'pending' ("IRN pending" at the desk and on the print). The invoice is
 *     already issued: the desk never waits for the IRP.</li>
 * <li>{@link #processDue} (EinvoiceWorker, every 15 s) claims due rows in a short transaction, calls the IRP with NO transaction
 *     open (ADR-02 / T-054 guard), and stores IRN, Ack No, Ack date and the signed QR in another short transaction. The claim
 *     counts the attempt and sets the next one (1, 2, 4 ... 60 minutes) before the call, and the row stays 'pending', so an
 *     unavailable IRP or a crash mid-call is retried by itself.</li>
 * </ul>
 * Not built: IRP cancellation within 24 h (a void after acceptance does not cancel the IRN), credit notes to the IRP, the
 * payload archive (request/response object keys), a production adapter (see {@link IrpPort}).
 */
@Service
public class EinvoiceService {

    static final String REGIME = "IN_IRP";
    static final String OWNER_ROLE = "Owner"; // the built-in role name, as billing.RefundService
    private static final int BATCH = 20;
    private static final int LIST_LIMIT = 50;
    private static final DateTimeFormatter ACK = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(EinvoiceService.class);

    private record Job(UUID id, UUID invoiceId, String payload) {
    }

    private final EinvoiceRepository repo;
    private final TenantContext tenantContext;
    private final TenantScope scope;
    private final ClinicClock clock;
    private final RegionRules regions;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ExternalCallGuard guard;
    private final ObjectProvider<IrpPort> adapter;
    private final ObjectMapper json;

    EinvoiceService(EinvoiceRepository repo, TenantContext tenantContext, TenantScope scope, ClinicClock clock, RegionRules regions,
                    ActorDirectory actors, AuditService audit, EventPublisher events, ExternalCallGuard guard, ObjectProvider<IrpPort> adapter,
                    ObjectMapper json) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.scope = scope;
        this.clock = clock;
        this.regions = regions;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.guard = guard;
        this.adapter = adapter;
        this.json = json;
    }

    /** Minutes to wait after the n-th failed attempt: 1, 2, 4, 8, 16, 32, then every 60. */
    static Duration backoff(int attempts) {
        return Duration.ofMinutes(Math.min(60, 1L << Math.min(Math.max(attempts, 1) - 1, 6)));
    }

    /** B2B tax invoice that is still standing: the only document that goes to the IRP. */
    static boolean applicable(EinvoiceRepository.InvoiceHead i) {
        return i.buyerGstin() != null && !i.buyerGstin().isBlank() && "invoice".equals(i.documentType()) && !"void".equals(i.status());
    }

    public boolean adapterAvailable() {
        return adapter.getIfAvailable() != null;
    }

    // ── issue ──

    /** Called by checkout, in its transaction, when the invoice is final (terminal billing stop, counter sale, package sale). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onIssued(UUID invoiceId) {
        UUID tenantId = tenantContext.current();
        if (!repo.irpEnabled(tenantId)) return; // the default: no row, no call, ever
        if (repo.invoice(tenantId, invoiceId).filter(EinvoiceService::applicable).isPresent()) {
            repo.enqueue(tenantId, invoiceId, REGIME, clock.now());
        }
    }

    // ── worker ──

    /** One clinic's due submissions. Returns the number of IRP calls made. Never call it inside a transaction. */
    public int processDue(UUID tenantId) {
        IrpPort irp = adapter.getIfAvailable();
        if (irp == null) return 0;
        List<Job> jobs = scope.run(tenantId, () -> claim(tenantId));
        for (Job j : jobs) {
            guard.assertNoTransaction("irp"); // T-054: the IRP call never holds a DB connection
            try {
                IrpPort.Accepted a = irp.submit(tenantId, j.payload());
                if (a == null || a.irn() == null || a.irn().isBlank()) throw new IllegalStateException("IRP answer without an IRN");
                scope.run(tenantId, () -> accept(tenantId, j, a));
            } catch (IrpRejectedException e) {
                String errors = errors("rejected", e.codes(), e.getMessage());
                scope.run(tenantId, () -> repo.rejected(tenantId, j.id(), errors));
                log.warn("e-invoice {} rejected by the IRP (tenant {}, codes {})", j.id(), tenantId, e.codes());
            } catch (RuntimeException e) {
                String errors = errors("unavailable", List.of(), "IRP unavailable");
                scope.run(tenantId, () -> repo.failed(tenantId, j.id(), errors));
                log.warn("e-invoice {} not submitted, will retry (tenant {}): {}", j.id(), tenantId, e.getClass().getSimpleName()); // log-phi-ok: ids and an exception class name
            }
        }
        return jobs.size();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Job> claim(UUID tenantId) {
        List<Job> out = new ArrayList<>();
        if (!repo.irpEnabled(tenantId)) return out; // switched off while rows were waiting: they wait, nothing is sent
        Instant now = clock.now();
        ZoneId zone = clock.zone(tenantId);
        for (EinvoiceRepository.Due d : repo.lockDue(tenantId, REGIME, now, BATCH)) {
            EinvoiceRepository.InvoiceHead inv = repo.invoice(tenantId, d.invoiceId()).orElse(null);
            if (inv == null || !applicable(inv)) {
                repo.cancelUnsent(tenantId, d.id());
                continue;
            }
            int attempts = d.attempts() + 1;
            repo.scheduleRetry(tenantId, d.id(), attempts, now.plus(backoff(attempts)));
            out.add(new Job(d.id(), d.invoiceId(), json.writeValueAsString(Inv01.build(repo.payloadSource(tenantId, inv, zone)))));
        }
        return out;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void accept(UUID tenantId, Job j, IrpPort.Accepted a) {
        if (repo.accepted(tenantId, j.id(), a) == 0) return; // no longer pending (cancelled meanwhile): keep what is there
        repo.bumpInvoiceVersion(tenantId, j.invoiceId());
        events.publish(tenantId, DomainEventType.EINVOICE_ACCEPTED, j.invoiceId(),
                Map.of("documentId", j.invoiceId(), "regime", REGIME, "externalId", a.irn()));
        audit.record(tenantId, "system", null, "IRP", "System", null, "billing.einvoice_accepted", "invoice", j.invoiceId(), null,
                Map.of("irn", a.irn(), "ackNo", String.valueOf(a.ackNo())));
    }

    private String errors(String kind, List<String> codes, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("codes", codes);
        m.put("message", message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message);
        m.put("at", clock.now().toString());
        return json.writeValueAsString(m);
    }

    // ── desk ──

    /** POST /v1/billing/invoices/{id}/einvoice/retry: a pending or rejected submission is due again now. 409 NOT_APPLICABLE otherwise. */
    @Transactional
    public EinvoiceDtos.RetryResponse retry(UUID staffId, String ip, UUID invoiceId) {
        UUID tenantId = tenantContext.current();
        EinvoiceRepository.InvoiceHead inv = repo.invoice(tenantId, invoiceId).orElseThrow(ApiException::notFound);
        if (!repo.irpEnabled(tenantId) || !applicable(inv)) {
            throw new ApiException(HttpStatus.CONFLICT, "einvoice-not-applicable", "No e-invoice for this bill",
                    "This bill does not go to the e-invoice portal: e-invoicing is off, or the bill has no buyer GSTIN, or it was voided.",
                    Map.of("code", "NOT_APPLICABLE"));
        }
        EinvoiceRepository.Doc doc = repo.find(tenantId, REGIME, invoiceId).orElse(null);
        if (doc == null) {
            repo.enqueue(tenantId, invoiceId, REGIME, clock.now()); // issued before the switch was turned on
        } else if ("pending".equals(doc.status()) || "rejected".equals(doc.status())) {
            repo.requeue(tenantId, doc.id(), clock.now());
        } else {
            return new EinvoiceDtos.RetryResponse(doc.status()); // accepted: nothing to do, the same answer every time
        }
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "billing.einvoice_retry_requested", "invoice", invoiceId,
                doc == null ? null : Map.of("status", doc.status()), Map.of("status", "pending"));
        return new EinvoiceDtos.RetryResponse("pending");
    }

    @Transactional(readOnly = true)
    public EinvoiceDtos.ListResponse list() {
        UUID tenantId = tenantContext.current();
        List<EinvoiceDtos.Item> items = repo.latest(tenantId, REGIME, LIST_LIMIT).stream().map(d -> new EinvoiceDtos.Item(d.invoiceId(), d.invoiceNumber(),
                d.status(), d.irn(), d.ackNo(), d.ackAt(), d.attempts(), d.nextAttemptAt(), "accepted".equals(d.status()) ? null : lastError(d.errors()),
                d.createdAt())).toList();
        return new EinvoiceDtos.ListResponse(repo.irpEnabled(tenantId), items);
    }

    private String lastError(String errors) {
        if (errors == null) return null;
        JsonNode n = json.readTree(errors);
        List<String> codes = new ArrayList<>();
        n.path("codes").forEach(c -> codes.add(c.asString()));
        String message = n.path("message").asString("");
        return codes.isEmpty() ? message : String.join(", ", codes) + (message.isEmpty() ? "" : ": " + message);
    }

    @Transactional(readOnly = true)
    public EinvoiceDtos.Settings settings() {
        return new EinvoiceDtos.Settings(repo.irpEnabled(tenantContext.current()), adapterAvailable());
    }

    /** PUT /v1/billing/einvoice/settings: the owner's switch. Turning it on needs a GST clinic and a deployed IRP adapter. */
    @Transactional
    public EinvoiceDtos.Settings setIrpEnabled(UUID staffId, String ip, boolean enabled) {
        UUID tenantId = tenantContext.current();
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        if (!OWNER_ROLE.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-required", "Owner only", "Only the owner can switch e-invoicing on or off.",
                    Map.of("code", "OWNER_REQUIRED"));
        }
        if (enabled && !"GST".equals(regions.region(clock.region(tenantId)).taxRegime())) {
            throw new ApiException(HttpStatus.CONFLICT, "einvoice-not-applicable", "Not a GST clinic",
                    "IRP e-invoicing is for clinics registered under Indian GST.", Map.of("code", "NOT_APPLICABLE"));
        }
        if (enabled && !adapterAvailable()) {
            throw new ApiException(HttpStatus.CONFLICT, "irp-not-configured", "E-invoice portal not connected",
                    "The e-invoice portal connection is not set up for this installation yet. Please contact Nabd support.",
                    Map.of("code", "IRP_NOT_CONFIGURED"));
        }
        boolean before = repo.irpEnabled(tenantId);
        if (before != enabled) {
            repo.setIrpEnabled(tenantId, enabled, staffId);
            audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "billing.einvoice_irp_switched", "tenant", tenantId,
                    Map.of("irpEnabled", before), Map.of("irpEnabled", enabled));
        }
        return new EinvoiceDtos.Settings(enabled, adapterAvailable());
    }

    // ── print ──

    /** The e-invoice lines of the printed invoice: IRN, Ack No and Ack date once registered, "IRN pending" until then; none for a bill that has no e-invoice. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> printLines(UUID invoiceId) {
        UUID tenantId = tenantContext.current();
        EinvoiceRepository.Doc d = repo.find(tenantId, REGIME, invoiceId).orElse(null);
        if (d == null || "cancelled".equals(d.status())) return List.of();
        if (!"accepted".equals(d.status())) return List.of("IRN: pending (e-invoice not yet registered)");
        List<String> out = new ArrayList<>();
        out.add("IRN: " + d.irn());
        out.add("Ack No: " + d.ackNo() + (d.ackAt() == null ? "" : "  Ack date: " + ACK.format(d.ackAt().atZone(clock.zone(tenantId)))));
        // shortcut: the signed QR is stored (einvoice_documents.qr_payload) but not drawn: SimplePdf has no images and the
        // codebase's QR encoder stops at 106 bytes; draw it when a full QR encoder is approved (the signed payload is ~1 kB).
        out.add("e-Invoice registered on the IRP. Signed QR code held on file.");
        return out;
    }
}
