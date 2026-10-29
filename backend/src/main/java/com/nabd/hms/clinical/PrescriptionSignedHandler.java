package com.nabd.hms.clinical;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantScope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** CLN-005: acknowledges clinical.prescription.signed.v1 so the dispatcher does not retry it; pharmacy worklist,
 * comms-gateway and clinical-intelligence consumers are later stories.
 * CLN-020: also counts the coded lines into drug_usage (typeahead ranking); a redelivery counts nothing twice. */
@Component
class PrescriptionSignedHandler implements OutboxEventHandler {

    private final DrugUsageService usage;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    PrescriptionSignedHandler(DrugUsageService usage, TenantScope tenantScope, ObjectMapper json) {
        this.usage = usage;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    @Override
    public String eventType() {
        return PrescriptionService.SIGNED_EVENT;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        UUID id = UUID.fromString(json.readTree(event.payloadJson()).path("prescriptionId").asString());
        tenantScope.run(event.tenantId(), () -> usage.onSigned(id));
    }
}
