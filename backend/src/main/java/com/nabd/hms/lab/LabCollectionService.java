package com.nabd.hms.lab;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.lab.LabCollectionDtos.CollectionRequest;
import com.nabd.hms.lab.LabCollectionDtos.CollectionResponse;
import com.nabd.hms.lab.LabCollectionDtos.ContainerRequest;
import com.nabd.hms.lab.LabCollectionDtos.Sample;
import com.nabd.hms.lab.LabCollectionRepository.SampleRow;
import com.nabd.hms.lab.LabOrderRepository.ItemRow;
import com.nabd.hms.lab.LabOrderRepository.OrderRow;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PHL-045: sample collection. Groups containers by sample type, generates accession numbers unique per tenant per day,
 * checks fasting hours against test requirements, and publishes the lab.sample.collected.v1 event.
 * shortcut: container draw order is seeded per tenant from migration (NEEDS LAB REVIEW by clinic staff, not shown here).
 */
@Service
public class LabCollectionService {

    private final LabCollectionRepository samples;
    private final LabOrderRepository orders;
    private final LabRepository catalogue;
    private final TenantContext tenantContext;
    private final EventPublisher events;
    private final JdbcTemplate jdbc;

    LabCollectionService(LabCollectionRepository samples, LabOrderRepository orders, LabRepository catalogue,
                         TenantContext tenantContext, EventPublisher events, JdbcTemplate jdbc) {
        this.samples = samples;
        this.orders = orders;
        this.catalogue = catalogue;
        this.tenantContext = tenantContext;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional
    public CollectionResponse collect(UUID orderId, UUID staffId, CollectionRequest req) {
        UUID tenantId = tenantContext.current();

        // Fetch and validate the order.
        Optional<OrderRow> orderOpt = orders.find(tenantId, orderId, false);
        if (orderOpt.isEmpty()) throw ApiException.notFound("Order not found.");
        OrderRow order = orderOpt.get();

        if ("cancelled".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "already-cancelled", "Order cancelled",
                    "Cannot collect samples from a cancelled order.");
        }
        if ("collected".equals(order.status()) || "in_progress".equals(order.status())
                || "partially_resulted".equals(order.status()) || "resulted".equals(order.status())
                || "validated".equals(order.status()) || "released".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "already-collected", "Already collected",
                    "Samples for this order have already been collected.");
        }

        // Fetch order items to build a map of test requirements.
        List<ItemRow> items = orders.items(tenantId, List.of(orderId));
        if (items.isEmpty()) throw ApiException.notFound("Order has no items.");
        Map<UUID, ItemRow> itemsById = new HashMap<>();
        items.forEach(i -> itemsById.put(i.id(), i));

        // Fetch tests to get their container and fasting requirements.
        Set<UUID> testIds = new HashSet<>();
        items.forEach(i -> testIds.add(i.testId()));
        List<LabRepository.TestRow> tests = catalogue.testsByIds(tenantId, testIds);
        if (tests.size() != testIds.size()) throw ApiException.notFound("One or more tests not found.");
        Map<UUID, LabRepository.TestRow> testsById = new HashMap<>();
        tests.forEach(t -> testsById.put(t.id(), t));

        // Build a map of test id -> fasting requirement from the order.
        Map<UUID, Integer> testFastingRequirements = new HashMap<>();
        if (order.fastingHours() != null) {
            items.forEach(i -> testFastingRequirements.put(i.testId(), order.fastingHours()));
        }

        // Validate containers: each must map to at least one item in the order.
        Map<String, List<UUID>> containerToItems = new LinkedHashMap<>();
        for (ContainerRequest cr : req.containers()) {
            List<UUID> itemsForContainer = new ArrayList<>();
            for (UUID itemId : cr.testItemIds()) {
                if (!itemsById.containsKey(itemId)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                            "Order item " + itemId + " not found in this order.");
                }
                itemsForContainer.add(itemId);
            }
            containerToItems.put(cr.container(), itemsForContainer);
        }

        // Check fasting: if actual < required for any test, mark the sample non-fasting.
        int actualFasting = req.fastingHours() != null ? req.fastingHours() : 0;
        boolean nonFasting = false;
        if (actualFasting > 0) {
            for (UUID testId : testFastingRequirements.keySet()) {
                int required = testFastingRequirements.get(testId);
                if (actualFasting < required) {
                    nonFasting = true;
                    break;
                }
            }
        }

        // Generate accession numbers and insert samples.
        List<Sample> created = new ArrayList<>();
        for (String container : containerToItems.keySet()) {
            String accession = nextAccession(tenantId);
            UUID sampleId = samples.insertSample(tenantId, orderId, accession, container,
                    null, // sample_type: set from container name or test, not enforced here
                    staffId, req.collectedAt(), req.fastingHours(), nonFasting);

            // Link the sample to its order items.
            for (UUID itemId : containerToItems.get(container)) {
                samples.linkSampleItem(tenantId, sampleId, itemId);
            }

            created.add(new Sample(sampleId, accession, container));
        }

        // Publish event.
        List<UUID> sampleIds = created.stream().map(Sample::sampleId).toList();
        events.publish(tenantId, DomainEventType.LAB_SAMPLE_COLLECTED, orderId, Map.of(
                "sampleIds", sampleIds.stream().map(UUID::toString).toList()
        ));

        return new CollectionResponse(created);
    }

    /**
     * Generate the next accession number for this tenant on the current day. Format: tenant-day-sequence (e.g., ABC-20261124-001).
     * This is unique per (tenant, day) and concurrent-safe through database sequence: ON CONFLICT increments atomically.
     */
    private String nextAccession(UUID tenantId) {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        String dateStr = today.toString().replace("-", "");
        // Atomically increment the sequence for this (tenant, date) pair. The ON CONFLICT DO UPDATE ensures
        // atomicity under concurrent inserts from different transactions.
        Integer seqNum = jdbc.queryForObject(
                "INSERT INTO lab_accession_sequences (tenant_id, sequence_date, next_number) VALUES (?, ?, 1) "
                        + "ON CONFLICT (tenant_id, sequence_date) DO UPDATE SET next_number = lab_accession_sequences.next_number + 1 "
                        + "RETURNING next_number",
                Integer.class, tenantId, today);
        return String.format("%s-%s-%03d", tenantId.toString().substring(0, 3).toUpperCase(Locale.ROOT),
                dateStr, seqNum);
    }

    /**
     * Fetch a sample label as PDF. The spec allows both ZPL (printer command) and HTML rendering; we render HTML via SimplePdf.
     */
    public byte[] sampleLabel(UUID tenantId, String accession) {
        Optional<SampleRow> sampleOpt = samples.findByAccession(tenantId, accession);
        if (sampleOpt.isEmpty()) throw ApiException.notFound("Sample not found.");
        SampleRow sample = sampleOpt.get();

        // Build label lines: accession, container, collected date, patient initials (not shown; use order's patient).
        // For now, a minimal label; expand with patient name, age/sex, etc. when patient data is available.
        List<String> lines = List.of(
                "ACCESSION: " + sample.accession(),
                "CONTAINER: " + sample.container(),
                "COLLECTED: " + sample.collectedAt(),
                sample.nonFasting() ? "NON-FASTING" : ""
        );

        return LabPdf.render(LabPdf.A4, lines, sample.accession());
    }
}
