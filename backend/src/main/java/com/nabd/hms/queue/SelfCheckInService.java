package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.api.SelfCheckIn;
import com.nabd.hms.queue.dto.CheckInRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/** IDC-018: SelfCheckIn over QueueService.checkIn (no staff caller; the audit row and queue event are written as the system). */
@Service
class SelfCheckInService implements SelfCheckIn {

    private final QueueService queue;
    private final QueueRepository queueRepo;
    private final ScheduleRepository scheduleRepo;
    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    SelfCheckInService(QueueService queue, QueueRepository queueRepo, ScheduleRepository scheduleRepo, JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock) {
        this.queue = queue;
        this.queueRepo = queueRepo;
        this.scheduleRepo = scheduleRepo;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Refusal> refusal(UUID doctorId) {
        UUID tid = tenantContext.current();
        if (queueRepo.findCheckInDepartment(tid, doctorId).isEmpty()) {
            return Optional.of(Refusal.NO_DOCTOR);
        }
        ZoneId zone = clock.zone(tid);
        LocalDate today = clock.today(tid);
        var block = scheduleRepo.findBlockCovering(doctorId, today.getDayOfWeek().getValue() % 7, clock.now().atZone(zone).toLocalTime());
        if (block.isEmpty()) {
            return Optional.of(Refusal.CLOSED);
        }
        Integer cap = block.get().maxPatients();
        if (cap != null && scheduleRepo.countSessionOccupancy(tid, doctorId, today, block.get().startTime(), block.get().endTime(), zone) >= cap) {
            return Optional.of(Refusal.FULL);
        }
        return Optional.empty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Token checkIn(UUID patientId, UUID doctorId, String source) {
        UUID tid = tenantContext.current();
        var open = jdbc.query("SELECT id, token_number FROM queue_entries WHERE tenant_id = ? AND patient_id = ? AND doctor_id = ? AND queue_date = ? " +
                        "AND status NOT IN ('completed', 'no_show', 'transferred_out') ORDER BY created_at LIMIT 1",
                (rs, i) -> new Token(rs.getObject(1, UUID.class), rs.getInt(2)), tid, patientId, doctorId, clock.today(tid));
        if (!open.isEmpty()) { // a second scan must not mint a second token
            return open.get(0);
        }
        var e = queue.checkIn(tid, null, new CheckInRequest(null, patientId, doctorId, source, null, null));
        return new Token(e.id(), e.tokenNumber());
    }
}
