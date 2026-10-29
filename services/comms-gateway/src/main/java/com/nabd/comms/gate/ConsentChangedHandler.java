package com.nabd.comms.gate;

import com.nabd.comms.consumer.CommsEventHandler;
import com.nabd.comms.store.ConsentSnapshotStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

/**
 * IDC-006.2: consent.changed.v1 (Core's one consent event, STOP / portal / desk withdrawals included) updates the channel-consent
 * snapshot and cancels what is still waiting to be sent. Runs inside the consumer's ledger transaction. Cancelling is the early
 * exit; the send-time {@link SendGate} is the guarantee, so a send that races this handler is still refused.
 */
@Component
public class ConsentChangedHandler implements CommsEventHandler {

    private final ConsentSnapshotStore snapshot;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConsentChangedHandler(ConsentSnapshotStore snapshot, JdbcTemplate jdbc, ObjectMapper json) {
        this.snapshot = snapshot;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Set<String> types() {
        return Set.of("consent.changed.v1");
    }

    @Override
    public void handle(Event event) {
        JsonNode d = json.readTree(event.dataJson());
        UUID patient = UUID.fromString(d.get("patientId").asString());
        String type = d.get("consentType").asString();
        String status = d.get("status").asString();
        boolean granted = "granted".equals(status);
        long version = d.get("version").asLong();
        boolean changed = snapshot.applyChanged(event.tenantId(), patient, type, status, version);
        if (changed && !granted) {
            // messaging withdrawn: everything not yet sent; marketing withdrawn: the promotional templates only
            String promo = "marketing".equals(type) ? "AND template_id IN (SELECT id FROM whatsapp_templates WHERE category = 'MARKETING') "
                    : "AND (template_id IS NULL OR template_id IN (SELECT id FROM whatsapp_templates WHERE category = 'UTILITY')) ";
            if ("messaging".equals(type) || "marketing".equals(type)) {
                jdbc.update("UPDATE outbound_messages SET status = 'cancelled', error_code = 'consent-withdrawn', updated_at = now() "
                        + "WHERE patient_ref = ? AND status IN ('scheduled','queued') " + promo, patient);
            }
        }
    }
}
