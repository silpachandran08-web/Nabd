package com.nabd.hms.packages;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.shared.booking.PackageSessionPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** COM-044: 'Book session N of M' from WhatsApp. Runs in the caller's (booking) transaction, bound to the clinic. */
@Service
class PackageSessionService implements PackageSessionPort {

    private final PackageRepository packages;
    private final PackageMessagingRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    PackageSessionService(PackageRepository packages, PackageMessagingRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.packages = packages;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<Bookable> bookable(UUID patientId) {
        UUID tenantId = tenantContext.current();
        return repo.sessionsOfPatient(tenantId, patientId).stream()
                .filter(s -> s.used() + s.booked() < s.total() && actionable(tenantId, s.instanceId()))
                .map(s -> new Bookable(s.instanceId(), s.used() + s.booked() + 1, s.total(), repo.eligibleDoctors(tenantId, s.packageId())))
                .toList();
    }

    @Override
    @Transactional
    public void requireBookable(UUID patientId, UUID instanceId, UUID doctorId) {
        Bookable b = bookable(patientId).stream().filter(x -> x.instanceId().equals(instanceId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "package-not-bookable", "Package session not bookable",
                        "This package has no session left to book.", Map.of("code", "PACKAGE_NOT_BOOKABLE")));
        if (!b.eligibleDoctorIds().isEmpty() && !b.eligibleDoctorIds().contains(doctorId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "doctor-not-eligible", "Doctor not eligible",
                    "This package can only be used with its eligible doctors.", Map.of("code", "DOCTOR_NOT_ELIGIBLE"));
        }
    }

    @Override
    @Transactional
    public void linkAppointment(UUID patientId, UUID instanceId, UUID appointmentId) {
        UUID tenantId = tenantContext.current();
        packages.lockInstance(tenantId, instanceId); // serialises two bookings of the last session (and FIN-004 refunds)
        Bookable b = bookable(patientId).stream().filter(x -> x.instanceId().equals(instanceId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "package-not-bookable", "Package session not bookable",
                        "This package has no session left to book.", Map.of("code", "PACKAGE_NOT_BOOKABLE")));
        UUID item = repo.itemWithRoom(tenantId, instanceId).orElseThrow();
        repo.insertBookedRedemption(tenantId, item, appointmentId);
        packages.insertEvent(tenantId, instanceId, "session_booked",
                "Session " + b.nextSession() + " of " + b.totalSessions() + " booked in WhatsApp · no entitlement consumed on booking", 0, null);
    }

    @Override
    @Transactional
    public void releaseAppointment(UUID appointmentId) {
        UUID tenantId = tenantContext.current();
        repo.releaseBooked(tenantId, appointmentId).ifPresent(instanceId -> packages.insertEvent(tenantId, instanceId, "session_released",
                "Appointment cancelled or missed · the session can be booked again", 0, null));
    }

    @Override
    @Transactional
    public void moveAppointment(UUID fromAppointmentId, UUID toAppointmentId) {
        repo.moveBooked(tenantContext.current(), fromAppointmentId, toAppointmentId);
    }

    @Transactional(propagation = Propagation.MANDATORY) // only from bookable(), inside its transaction
    private boolean actionable(UUID tenantId, UUID instanceId) {
        return packages.findInstance(tenantId, instanceId)
                .map(r -> List.of("active", "grace").contains(PackageInstanceService.effectiveStatus(r, clock.today(tenantId))))
                .orElse(false);
    }
}
