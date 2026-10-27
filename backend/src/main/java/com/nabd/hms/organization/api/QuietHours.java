package com.nabd.hms.organization.api;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/** ADM-028: the clinic's quiet hours for patient messages, in the clinic's own clock. Empty when switched off. Implemented by organization.setup.CalendarService. */
public interface QuietHours {

    record Window(LocalTime from, LocalTime to) {
    }

    Optional<Window> window(UUID tenantId);
}
