package com.nabd.edge;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * COM-016.1: everything a bot reply may show about a clinic (name, time zone, doctors, free days and slots), bound to the ONE tenant
 * of the conversation when the step starts. No method takes a tenant id, so a flow holding a ClinicContent cannot ask for another
 * clinic's doctors, days or slots. Every profile read also teaches the OutputGuard what this clinic owns.
 */
final class ClinicContent {

    private final UUID tenantId;
    private final CoreClient core;
    private final AvailabilityProjection projection;
    private final OutputGuard guard;

    private ClinicContent(UUID tenantId, CoreClient core, AvailabilityProjection projection, OutputGuard guard) {
        this.tenantId = tenantId;
        this.core = core;
        this.projection = projection;
        this.guard = guard;
    }

    CoreClient.Profile profile() {
        CoreClient.Profile p = core.profile(tenantId);
        guard.learn(tenantId, p);
        return p;
    }

    /** Clinic days with at least one free slot of this clinic's doctor. */
    List<LocalDate> freeDays(UUID doctorId, LocalDate from, LocalDate to) {
        return core.freeDays(tenantId, doctorId, from, to);
    }

    /** The doctor's sessions for the day from the availability projection (cached per tenant). */
    JsonNode sessions(UUID doctorId, LocalDate day) {
        return projection.read(tenantId, doctorId, day);
    }

    /** The doctor's sessions for the day straight from Core. */
    JsonNode doctorDay(UUID doctorId, LocalDate day) {
        return core.doctorDay(tenantId, doctorId, day);
    }

    /** Binds the content to the conversation's tenant (set at conversation start, never changed). */
    @Component
    static class Provider {

        private final CoreClient core;
        private final AvailabilityProjection projection;
        private final OutputGuard guard;

        Provider(CoreClient core, AvailabilityProjection projection, OutputGuard guard) {
            this.core = core;
            this.projection = projection;
            this.guard = guard;
        }

        ClinicContent of(FlowHandler.Conversation c) {
            return new ClinicContent(c.tenantId(), core, projection, guard);
        }
    }
}
