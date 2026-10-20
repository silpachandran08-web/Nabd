package com.nabd.hms.patient.registry;

import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * OPS-017: the 'projector' consumer group. An allergy change (subject = the patient) re-projects that patient's registry
 * row in the event's tenant transaction, next to the ledger row. Version-gated: a late v=4 after v=5 is ignored, which
 * is safe because the projection is recomputed from the tables, so v=5 already reflects everything before it.
 * The V221 triggers + RegistryProjector.drain() poller stay until OPS-016's relay runs in every region.
 */
@Component
class RegistryEventConsumer implements EventConsumer {

    static final String ALLERGY_CHANGED = "clinical.allergy.changed.v1";

    private final RegistryProjector projector;

    RegistryEventConsumer(RegistryProjector projector) {
        this.projector = projector;
    }

    @Override
    public String group() {
        return "projector";
    }

    @Override
    public Set<String> types() {
        return Set.of(ALLERGY_CHANGED);
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.DB_LEDGER;
    }

    @Override
    public boolean versionGated() {
        return true;
    }

    @Override
    public void handle(Event event) {
        projector.project(event.tenantId(), List.of(UUID.fromString(event.data().path("patientId").asText())));
    }
}
