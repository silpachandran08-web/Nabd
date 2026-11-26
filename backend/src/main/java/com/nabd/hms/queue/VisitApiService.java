package com.nabd.hms.queue;

import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.queue.api.VisitCommands;
import com.nabd.hms.queue.api.VisitQueries;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PLT-035: the visit module's published API (queue.api.VisitCommands / VisitQueries) and the billing.invoice.settled.v1 reaction. */
@Service
class VisitApiService implements VisitCommands, VisitQueries {

    private static final Logger log = LoggerFactory.getLogger(VisitApiService.class);
    private static final Set<String> BILLING_STOPS = Set.of("billing_pending", "checkout_pending");

    private final QueueService queue;
    private final QueueRepository repo;
    private final OrganizationConfig flows;

    VisitApiService(QueueService queue, QueueRepository repo, OrganizationConfig flows) {
        this.queue = queue;
        this.repo = repo;
        this.flows = flows;
    }

    @Override
    @Transactional
    public void completeVisit(UUID tenantId, UUID staffId, UUID visitId, String expectedStatus) {
        queue.move(tenantId, staffId, visitId, expectedStatus, "completed");
    }

    @Override
    @Transactional
    public void advanceAfterBilling(UUID tenantId, UUID staffId, UUID visitId, String expectedStatus, String nextStatus) {
        queue.updateStatus(tenantId, staffId, visitId, new QueueStatusUpdateRequest(nextStatus, expectedStatus));
    }

    /**
     * billing.invoice.settled.v1: the visit's bill is settled, so a visit still waiting at the terminal billing stop of its flow is
     * completed (same CAS move as checkout). Checkout already completes it in its own transaction, so a delivery normally finds it
     * completed and changes nothing; redeliveries are no-ops too. Returns true when this call completed the visit.
     */
    @Transactional
    boolean completeSettled(UUID tenantId, UUID settledBy, UUID visitId) {
        QueueModels.QueueEntryRow row = repo.findById(tenantId, visitId).orElse(null);
        if (row == null || !BILLING_STOPS.contains(row.status())) {
            return false; // unknown, already completed, or not at a billing stop: nothing to do
        }
        List<String> sequence = flows.resolveStatusSequence(tenantId, row.departmentId());
        int i = sequence.indexOf(row.status());
        if (i < 0 || i + 1 >= sequence.size() || !"completed".equals(sequence.get(i + 1))) {
            log.info("invoice settled for visit {} at interim stop {}; not completed", visitId, row.status()); // ids only
            return false;
        }
        queue.move(tenantId, settledBy, visitId, row.status(), "completed");
        return true;
    }

    @Override
    @Transactional
    public Optional<Visit> visit(UUID tenantId, UUID visitId) {
        return repo.visit(tenantId, visitId);
    }

    @Override
    @Transactional
    public Optional<java.time.LocalDate> visitDate(UUID tenantId, UUID visitId) {
        return repo.visitDate(tenantId, visitId);
    }

    @Override
    @Transactional
    public List<VisitDay> completedVisits(UUID tenantId, UUID patientId, UUID doctorId) {
        return repo.completedVisits(tenantId, patientId, doctorId);
    }

    @Override
    @Transactional
    public int atBillingStop(UUID tenantId, LocalDate day) {
        return repo.atBillingStop(tenantId, day);
    }

    @Override
    @Transactional
    public List<AwaitingCheckout> awaitingCheckout(UUID tenantId, LocalDate day) {
        return repo.awaitingCheckout(tenantId, day);
    }

    @Override
    @Transactional
    public DayCounts dayCounts(UUID tenantId, LocalDate day) {
        return repo.dayCounts(tenantId, day);
    }

    @Override
    @Transactional
    public List<NoShow> noShows(UUID tenantId, Instant from, Instant to, Instant now) {
        return repo.noShows(tenantId, from, to, now);
    }
}
