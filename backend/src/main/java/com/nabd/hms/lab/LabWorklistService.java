package com.nabd.hms.lab;

import com.nabd.hms.lab.LabWorklistDtos.Counts;
import com.nabd.hms.lab.LabWorklistDtos.Order;
import com.nabd.hms.lab.LabWorklistDtos.Worklist;
import com.nabd.hms.lab.LabWorklistRepository.StatusCounts;
import com.nabd.hms.lab.LabWorklistRepository.WorklistOrder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** PHL-044: lab worklist service. Fetches orders by status/priority/source, STAT first by TAT deadline. */
@Service
public class LabWorklistService {

    private static final int PAGE_SIZE = 100;

    private final LabWorklistRepository repo;

    LabWorklistService(LabWorklistRepository repo) {
        this.repo = repo;
    }

    public Worklist getWorklist(Jwt jwt, String status, String priority, String source) {
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));

        StatusCounts counts = repo.getCounts(tenantId);
        List<WorklistOrder> rows = repo.getOrders(tenantId, status, priority, source, PAGE_SIZE);

        List<Order> items = rows.stream()
                .map(r -> new Order(r.id(), r.patientId(), r.priority(), r.status(), r.source(), r.orderedAt(), r.dueAt(), r.testCount()))
                .toList();

        return new Worklist(
                new Counts(counts.ordered(), counts.collected(), counts.inProgress(), counts.resulted(), counts.validated()),
                items
        );
    }
}
