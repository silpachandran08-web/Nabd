package com.nabd.hms.platform.ticket;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.platform.ticket.dto.RaiseTicketRequest;
import com.nabd.hms.platform.ticket.dto.TicketResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.platform.ticket.TicketModels.Raiser;
import static com.nabd.hms.platform.ticket.TicketModels.Ticket;

/**
 * SSA-08: tickets raised by owners/staff/doctors (all just "staff" rows post-V6 — see
 * findRaiser), plus a 'system' source for future alert-driven tickets (NB-263/264 don't exist
 * yet, so nothing calls raiseFromSystem today; the path is ready for when they do).
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    // ponytail: fixed SLA-by-priority table, not a configurable policy engine — add one if a
    // future requirement needs per-tenant or per-plan SLA targets.
    private static final Map<String, Duration> SLA_BY_PRIORITY = Map.of(
            "urgent", Duration.ofHours(4),
            "high", Duration.ofHours(24),
            "normal", Duration.ofHours(72),
            "low", Duration.ofHours(120)
    );

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "open", Set.of("in_progress", "closed"),
            "in_progress", Set.of("resolved", "closed"),
            "resolved", Set.of("closed"),
            "closed", Set.of() // terminal
    );

    private final TicketRepository repo;
    private final TenantContext tenantContext;

    TicketService(TicketRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public TicketResponse raiseFromStaff(UUID tenantId, UUID staffId, RaiseTicketRequest req) {
        tenantContext.set(tenantId);
        Raiser raiser = repo.findRaiser(tenantId, staffId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The calling staff member was not found."));
        String priority = req.priorityOrDefault();
        Ticket ticket = new Ticket(UUID.randomUUID(), tenantId, null, null, "staff", raiser.staffId(), raiser.name(),
                raiser.email(), raiser.role(), req.subject(), req.description(), priority, "open",
                Instant.now().plus(SLA_BY_PRIORITY.get(priority)), null, null);
        repo.insert(ticket);
        log.info("ticket {} raised for tenant {} by staff {} ({}), priority {}",
                ticket.id(), tenantId, staffId, raiser.role(), priority);
        return toResponse(repo.findById(ticket.id()).orElseThrow());
    }

    /** No caller yet — see the class doc. Kept here so NB-263/264 have a one-line hook to raise into. */
    public TicketResponse raiseFromSystem(UUID tenantId, String subject, String description, String priority) {
        String effectivePriority = priority == null ? "normal" : priority;
        Ticket ticket = new Ticket(UUID.randomUUID(), tenantId, null, null, "system", null, "Nabd System", null,
                "System", subject, description, effectivePriority, "open",
                Instant.now().plus(SLA_BY_PRIORITY.get(effectivePriority)), null, null);
        repo.insert(ticket);
        log.info("ticket {} raised for tenant {} by system, priority {}", ticket.id(), tenantId, effectivePriority);
        return toResponse(repo.findById(ticket.id()).orElseThrow());
    }

    public List<TicketResponse> list() {
        return repo.listAll().stream().map(this::toResponse).toList();
    }

    public TicketResponse transition(UUID id, String toStatus) {
        Ticket ticket = repo.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested ticket was not found."));
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(ticket.status(), Set.of());
        if (!allowed.contains(toStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-ticket-transition", "Invalid ticket transition",
                    "A ticket in " + ticket.status() + " cannot move to " + toStatus + ".");
        }
        Instant resolvedAt = toStatus.equals("resolved") ? Instant.now() : ticket.resolvedAt();
        repo.updateStatus(id, toStatus, resolvedAt);
        log.info("ticket {} transitioned {} -> {}", id, ticket.status(), toStatus);
        return toResponse(repo.findById(id).orElseThrow());
    }

    private TicketResponse toResponse(Ticket t) {
        boolean breached = Set.of("open", "in_progress").contains(t.status()) && t.slaDueAt().isBefore(Instant.now());
        return new TicketResponse(t.id(), t.tenantId(), t.tenantName(), t.tenantSlug(), t.source(), t.raisedByName(),
                t.raisedByEmail(), t.raisedByRole(), t.subject(), t.description(), t.priority(), t.status(),
                t.slaDueAt(), breached, t.resolvedAt(), t.createdAt());
    }
}
