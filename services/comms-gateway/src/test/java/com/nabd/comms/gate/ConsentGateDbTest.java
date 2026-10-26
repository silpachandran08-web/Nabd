package com.nabd.comms.gate;

import com.nabd.comms.consumer.CommsEventHandler;
import com.nabd.comms.gate.SendGate.Category;
import com.nabd.comms.gate.SendGate.Request;
import com.nabd.comms.store.ConsentSnapshotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-006.3 / IDC-008: send-time consent gate, withdrawal handler, child-subject refusal incl. the 18th-birthday boundary in two zones. */
class ConsentGateDbTest {

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("comms_gate");
    static JdbcTemplate app, su;
    static TransactionTemplate tx;
    static ConsentSnapshotStore store;
    static SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeAll
    static void start() throws Exception {
        PG.start();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE comms_app LOGIN PASSWORD 'x' NOSUPERUSER NOBYPASSRLS");
            for (String f : new String[] {"V3__outbound_messages.sql", "V14__send_refusals.sql"}) {
                s.execute(Files.readString(Path.of("src/main/resources/db/migration/" + f)));
            }
            s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO comms_app");
        }
        su = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), "comms_app", "x");
        app = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new ConsentSnapshotStore(app);
    }

    final UUID tenant = UUID.randomUUID(), other = UUID.randomUUID();

    static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    SendGate gate(Clock clock) {
        return new SendGate(store, app, meters, clock);
    }

    SendGate.Decision check(SendGate g, Request r) {
        return tx.execute(s -> g.check(r));
    }

    Request req(UUID patient, Category c, boolean promo, LocalDate dob, String zone) {
        return new Request(tenant, patient, null, c, promo, dob, ZoneId.of(zone));
    }

    @Test
    void marketingIsRefusedWithoutConsentAndTheRefusalIsRecordedAndCounted() {
        SendGate g = gate(at("2026-06-01T10:00:00Z"));
        UUID p = UUID.randomUUID();
        var d = check(g, req(p, Category.MARKETING, false, null, "Asia/Kolkata"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("no-marketing-consent");
        assertThat(meters.counter("comms_send_refused_total", "reason", "no-marketing-consent").count()).isGreaterThanOrEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM send_refusals WHERE tenant_id = ? AND patient_ref = ? AND reason = 'no-marketing-consent'", Integer.class, tenant, p)).isEqualTo(1);
        tx.executeWithoutResult(s -> store.apply(tenant, p, "marketing", true, 1));
        assertThat(check(g, req(p, Category.MARKETING, false, null, "Asia/Kolkata")).allowed()).isTrue();
        assertThat(check(g, req(p, Category.UTILITY, false, null, "Asia/Kolkata")).reason()).as("marketing consent is not messaging consent").isEqualTo("no-messaging-consent");
        assertThat(check(g, req(p, Category.AUTHENTICATION, false, null, "Asia/Kolkata")).allowed()).as("one-time codes need no channel consent").isTrue();
    }

    @Test
    void aStopWithdrawsMessagingCancelsWhatIsQueuedAndTheSendTimeGateRefusesTheRest() throws Exception {
        UUID p = UUID.randomUUID();
        UUID tpl = UUID.randomUUID();
        tx.executeWithoutResult(s -> store.apply(tenant, p, "messaging", true, 1));
        su.update("INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status) VALUES (?,?,'n',?,'en','UTILITY','h','b',0,'approved')",
                tpl, tenant, "m" + tpl.toString().replace("-", ""));
        UUID queued = UUID.randomUUID();
        su.update("INSERT INTO outbound_messages (id, tenant_id, patient_ref, to_address_enc, to_hash, template_id, template_name, language, status) VALUES (?,?,?,'\\x00','\\x00',?,'n','en','queued')",
                queued, tenant, p, tpl);
        var handler = new ConsentChangedHandler(store, app, new ObjectMapper());
        String stop = "{\"patientId\":\"" + p + "\",\"consentType\":\"messaging\",\"status\":\"withdrawn\",\"version\":2}";
        tx.executeWithoutResult(s -> handler.handle(new CommsEventHandler.Event("e1", "consent.changed.v1", tenant, stop)));
        assertThat(su.queryForObject("SELECT status || '/' || error_code FROM outbound_messages WHERE id = ?", String.class, queued)).isEqualTo("cancelled/consent-withdrawn");
        assertThat(check(gate(at("2026-06-01T10:00:00Z")), req(p, Category.UTILITY, false, null, "UTC")).reason()).isEqualTo("no-messaging-consent");
        tx.executeWithoutResult(s -> handler.handle(new CommsEventHandler.Event("e1", "consent.changed.v1", tenant, stop))); // replay: version gate, no change
        tx.executeWithoutResult(s -> handler.handle(new CommsEventHandler.Event("e0", "consent.changed.v1", tenant,
                "{\"patientId\":\"" + p + "\",\"consentType\":\"messaging\",\"status\":\"granted\",\"version\":1}")));
        assertThat(check(gate(at("2026-06-01T10:00:00Z")), req(p, Category.UTILITY, false, null, "UTC")).allowed()).as("an older grant cannot undo the STOP").isFalse();
    }

    @Test
    void aChildSubjectNeverGetsMarketingEvenWithAConsentSnapshotAndAlsoWhenSentToTheGuardianNumber() {
        UUID child = UUID.randomUUID();
        tx.executeWithoutResult(s -> store.apply(tenant, child, "marketing", true, 1)); // e.g. a stale or wrong snapshot: the age rule still wins
        SendGate g = gate(at("2026-06-01T10:00:00Z"));
        LocalDate dob17y364d = LocalDate.of(2008, 6, 2); // turns 18 tomorrow
        for (Category c : new Category[] {Category.MARKETING, Category.UTILITY}) {
            var d = check(g, req(child, c, c == Category.UTILITY, dob17y364d, "Asia/Kolkata"));
            assertThat(d.reason()).as(c + " promo").isEqualTo("minor-subject");
        }
        // care messages about a child are not blocked by age: only the (absent) messaging consent refuses them
        assertThat(check(g, req(child, Category.UTILITY, false, dob17y364d, "Asia/Kolkata")).reason()).isEqualTo("no-messaging-consent");
    }

    @Test
    void eighteenthBirthdayIsDecidedInTheClinicZone() {
        // 2026-06-01T20:00Z is already 2 June in Kolkata (+05:30) but still 1 June in Los Angeles-like zones; Dubai is +04:00 (2 June 00:00 at 20:00Z).
        Clock c = at("2026-06-01T20:00:00Z");
        LocalDate dob = LocalDate.of(2008, 6, 2);
        assertThat(MinorSubject.isMinor(dob, ZoneId.of("Asia/Kolkata"), c)).as("18 today in Kolkata").isFalse();
        assertThat(MinorSubject.isMinor(dob, ZoneId.of("Asia/Dubai"), c)).as("18 today in Dubai").isFalse();
        assertThat(MinorSubject.isMinor(dob, ZoneId.of("America/Los_Angeles"), c)).as("still 1 June there").isTrue();
        assertThat(MinorSubject.isMinor(dob, ZoneId.of("UTC"), at("2026-06-02T00:00:00Z"))).isFalse();
        assertThat(MinorSubject.isMinor(dob, ZoneId.of("UTC"), at("2026-06-01T23:59:59Z"))).isTrue();
        assertThat(MinorSubject.isMinor(LocalDate.of(2008, 2, 29), ZoneId.of("UTC"), at("2026-02-28T12:00:00Z"))).as("29 Feb: protected until 1 March").isTrue();
        assertThat(MinorSubject.isMinor(LocalDate.of(2008, 2, 29), ZoneId.of("UTC"), at("2026-03-01T00:00:00Z"))).isFalse();
    }

    @Test
    void turning18AllowsMarketingOnlyAfterTheirOwnConsentAndTenantsAreIsolated() {
        UUID p = UUID.randomUUID();
        LocalDate dob = LocalDate.of(2008, 6, 2);
        SendGate g = gate(at("2026-06-02T08:00:00Z"));
        assertThat(check(g, req(p, Category.MARKETING, false, dob, "Asia/Kolkata")).reason()).as("18 today but no own consent yet").isEqualTo("no-marketing-consent");
        tx.executeWithoutResult(s -> store.apply(tenant, p, "marketing", true, 1));
        assertThat(check(g, req(p, Category.MARKETING, false, dob, "Asia/Kolkata")).allowed()).isTrue();
        var otherTenant = new Request(other, p, null, Category.MARKETING, false, null, ZoneId.of("UTC"));
        assertThat(check(g, otherTenant).reason()).as("tenant B has no snapshot for this ref").isEqualTo("no-marketing-consent");
        assertThat(su.queryForObject("SELECT count(*) FROM send_refusals WHERE tenant_id = ?", Integer.class, other)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT count(*) FROM send_refusals", Integer.class)).as("no tenant bound: nothing visible").isZero();
    }
}
