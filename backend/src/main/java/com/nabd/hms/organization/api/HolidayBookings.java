package com.nabd.hms.organization.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/** ADM-028: how many booked appointments sit on each clinic day (the owner's "12 to move"). Implemented by the queue module; call inside a tenant transaction. */
public interface HolidayBookings {

    Map<LocalDate, Integer> scheduledOn(Collection<LocalDate> days);
}
