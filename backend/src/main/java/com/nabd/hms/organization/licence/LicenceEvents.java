package com.nabd.hms.organization.licence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.iam.api.StaffLicenceSync;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-032: consumers of the two licence events. They are outbox events outside the domain catalogue (like the PHI read alert):
 * ids, a day count and a date only. Expiring: one WhatsApp utility message per recipient (holder and owners; a facility licence
 * goes to owners only) through the notification port, deduplicated per licence, threshold, expiry date and recipient, so an
 * outbox retry never sends twice. A clinic without an approved licence_expiring template makes the handler throw: the outbox
 * retries and then dead-letters visibly. Lapsed: log-only here; scheduling and clinical read the doctor's flags directly.
 */
public final class LicenceEvents {

    public static final String EXPIRING = "organization.licence.expiring.v1";
    public static final String LAPSED = "organization.licence.lapsed.v1";

    private LicenceEvents() {
    }

    @Component
    static class Expiring implements OutboxEventHandler {

        private final ObjectMapper mapper;
        private final JdbcTemplate jdbc;
        private final StaffLicenceSync staff;
        private final NotificationPort notifications;

        Expiring(ObjectMapper mapper, JdbcTemplate jdbc, StaffLicenceSync staff, NotificationPort notifications) {
            this.mapper = mapper;
            this.jdbc = jdbc;
            this.staff = staff;
            this.notifications = notifications;
        }

        @Override
        public String eventType() {
            return EXPIRING;
        }

        @Override
        public void handle(OutboxEvent e) throws Exception {
            JsonNode p = mapper.readTree(e.payloadJson());
            UUID licence = UUID.fromString(p.path("licenceId").asString());
            int threshold = p.path("threshold").asInt();
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT licence_type, expiry_date::text AS expiry, holder_name FROM licence_registry WHERE tenant_id = app_tenant_id() AND id = ?", licence);
            if (rows.isEmpty()) {
                return; // deleted meanwhile
            }
            Map<String, Object> row = rows.get(0);
            Map<String, StaffLicenceSync.Contact> to = new LinkedHashMap<>();
            if (p.hasNonNull("holderStaffId")) {
                staff.contact(UUID.fromString(p.path("holderStaffId").asString())).ifPresent(c -> to.put("holder", c));
            }
            List<StaffLicenceSync.Contact> owners = new ArrayList<>(staff.owners());
            for (StaffLicenceSync.Contact o : owners) {
                to.putIfAbsent("owner:" + o.staffId(), o);
            }
            String expiry = (String) row.get("expiry");
            String days = String.valueOf(p.path("daysLeft").asLong());
            String holder = row.get("holder_name") == null ? "" : (String) row.get("holder_name");
            for (Map.Entry<String, StaffLicenceSync.Contact> r : to.entrySet()) {
                String phone = r.getValue().phone();
                if (phone == null || phone.isBlank()) {
                    continue;
                }
                notifications.request(new NotificationRequest(NotificationPurpose.LICENCE_EXPIRING, "licence", licence, null, phone, "en",
                        List.of(holder, String.valueOf(row.get("licence_type")), expiry, days), null, null, null,
                        "licence-expiring:" + licence + ":" + threshold + ":" + expiry + ":" + r.getValue().staffId()));
            }
        }
    }

    @Component
    static class Lapsed implements OutboxEventHandler {

        private static final Logger log = LoggerFactory.getLogger(Lapsed.class);

        @Override
        public String eventType() {
            return LAPSED;
        }

        @Override
        public void handle(OutboxEvent e) {
            log.debug("{} {} delivered (tenant {})", e.eventType(), e.id(), e.tenantId()); // ids only
        }
    }
}
