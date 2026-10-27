package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.HolidayBookings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/** ADM-028: the owner's "n to move" on a closed day: booked appointments that have not been seen, per clinic day. */
@Service
class HolidayBookingCounter implements HolidayBookings {

    private final AppointmentRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    HolidayBookingCounter(AppointmentRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Map<LocalDate, Integer> scheduledOn(Collection<LocalDate> days) {
        var tid = tenantContext.current();
        return repo.scheduledPerDay(tid, days, clock.zone(tid));
    }
}
