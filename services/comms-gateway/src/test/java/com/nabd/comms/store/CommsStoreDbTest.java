package com.nabd.comms.store;

import com.nabd.comms.sender.InboundRouter;
import com.nabd.comms.sender.JdbcRouteStore;
import com.nabd.comms.sender.JdbcSenderDirectory;
import com.nabd.comms.sender.ButtonTokens;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-008 / COM-011 against a real Postgres: V3-V5 migrations, RLS, version gate, reconciliation, backfill, route store. */
class CommsStoreDbTest {

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("comms_test");
    static JdbcTemplate app, su;
    static TransactionTemplate tx;
    static DataSourceTransactionManager tm;

    @BeforeAll
    static void start() throws Exception {
        PG.start();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE comms_app LOGIN PASSWORD 'x' NOSUPERUSER NOBYPASSRLS");
            s.execute("CREATE DATABASE core_src");
            for (String f : new String[] {"V3__outbound_messages.sql", "V4__backfill_markers.sql", "V5__sender_accounts_phone_routes.sql", "V22__template_versions.sql"}) {
                s.execute(Files.readString(Path.of("src/main/resources/db/migration/" + f)));
            }
            s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO comms_app");
        }
        su = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), "comms_app", "x");
        app = new JdbcTemplate(ds);
        tm = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(tm);
    }

    final UUID tA = UUID.randomUUID(), tB = UUID.randomUUID();

    // ---- COM-008 consent snapshot ----

    @Test
    void consentApplyIsVersionGatedAndAWithdrawalBlocksTheSend() {
        var store = new ConsentSnapshotStore(app);
        UUID p = UUID.randomUUID();
        assertThat(txb(() -> store.isGranted(tA, p, "messaging"))).as("no row = blocked").isFalse();
        assertThat(txb(() -> store.apply(tA, p, "messaging", true, 5))).isTrue();
        assertThat(txb(() -> store.isGranted(tA, p, "messaging"))).isTrue();
        assertThat(txb(() -> store.apply(tA, p, "messaging", false, 3))).as("older event ignored").isFalse();
        assertThat(txb(() -> store.apply(tA, p, "messaging", false, 5))).as("same version ignored").isFalse();
        assertThat(txb(() -> store.isGranted(tA, p, "messaging"))).isTrue();
        assertThat(txb(() -> store.apply(tA, p, "messaging", false, 6))).isTrue();
        assertThat(txb(() -> store.isGranted(tA, p, "messaging"))).as("withdrawal visible, queued send blocked").isFalse();
        assertThat(txb(() -> store.isGranted(tA, p, "marketing"))).as("purposes are independent").isFalse();
        assertThat(txb(() -> store.applyChanged(tA, p, "guardian_access", "granted", 9))).as("not a channel consent").isFalse();
        assertThat(txb(() -> store.applyChanged(tA, p, "messaging", "granted", 7))).as("event shape: consentType/status/version").isTrue();
        assertThat(txb(() -> store.isGranted(tA, p, "messaging"))).isTrue();
    }

    @Test
    void snapshotIsTenantIsolated() {
        var store = new ConsentSnapshotStore(app);
        UUID p = UUID.randomUUID();
        tx.executeWithoutResult(s -> store.apply(tA, p, "messaging", true, 1));
        assertThat(txb(() -> store.isGranted(tB, p, "messaging"))).as("tenant B cannot see tenant A's row").isFalse();
        assertThat(su.queryForObject("SELECT count(*) FROM channel_consent_snapshot WHERE patient_ref = ?", Integer.class, p)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT count(*) FROM channel_consent_snapshot", Integer.class)).as("no tenant bound: nothing visible").isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tB.toString());
            app.update("INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?,?,?,?)",
                    tA, UUID.randomUUID(), "messaging", true, 1);
        })).rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void reconciliationCorrectsMissedEventsPagesThroughTheExportAndEmitsDrift() {
        var store = new ConsentSnapshotStore(app);
        UUID missedWithdrawal = UUID.randomUUID(), missedGrant = UUID.randomUUID(), inSync = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            store.apply(tA, missedWithdrawal, "messaging", true, 2); // the withdrawal event (v7) never arrived
            store.apply(tA, inSync, "messaging", true, 4);
        });
        var pages = Map.of(
                "", new ConsentReconciler.Page(List.of(new ConsentReconciler.Item(missedWithdrawal, "messaging", false, 7)), "c1"),
                "c1", new ConsentReconciler.Page(List.of(new ConsentReconciler.Item(inSync, "messaging", true, 4),
                        new ConsentReconciler.Item(missedGrant, "marketing", true, 9)), null));
        var metrics = new SimpleMeterRegistry();
        var rec = new ConsentReconciler(store, (t, cursor) -> pages.get(cursor == null ? "" : cursor), tx, metrics);
        assertThat(rec.reconcile(tA)).isEqualTo(2);
        assertThat(metrics.counter("comms_consent_drift_total").count()).isEqualTo(2.0);
        assertThat(txb(() -> store.isGranted(tA, missedWithdrawal, "messaging"))).isFalse();
        assertThat(txb(() -> store.isGranted(tA, missedGrant, "marketing"))).isTrue();
        assertThat(rec.reconcile(tA)).as("second run finds no drift").isZero();
        assertThat(txb(() -> store.apply(tA, missedWithdrawal, "messaging", true, 6)))
                .as("the late stale event cannot undo the correction (version kept at 7)").isFalse();
    }

    // ---- COM-008 backfill ----

    @Test
    void backfillOf100kMessagesMatchesCountsAndHistogramsIsIdempotentAndResumable() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl().replace("/comms_test", "/core_src"), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE whatsapp_templates (id uuid PRIMARY KEY, tenant_id uuid, name text, meta_name text, language text, category text,
                      header_text text, body text, param_count int, status text, rejection_reason text, quality_rating text, meta_template_id text,
                      catalogue_key text, catalogue_version int, buttons jsonb, purpose text, submit_attempts int, next_attempt_at timestamptz,
                      submitted_at timestamptz, created_at timestamptz, updated_at timestamptz,
                      version int NOT NULL DEFAULT 1, active boolean NOT NULL DEFAULT true, paused_until timestamptz, meta_category text); -- COM-013 (Core V3100)
                    CREATE TABLE whatsapp_messages (id uuid PRIMARY KEY, tenant_id uuid, patient_id uuid, to_phone text, template_name text,
                      language text, params jsonb, status text, wamid text, error text, template_id uuid, attempts int, last_attempt_at timestamptz,
                      created_at timestamptz, updated_at timestamptz);
                    CREATE TABLE whatsapp_inbound_events (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, tenant_id uuid, source text,
                      dedupe_key text, field text, phone_number_id text, body_enc bytea, status text, attempts int, locked_until timestamptz,
                      received_at timestamptz, processed_at timestamptz, last_error_code text);
                    INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status,
                      quality_rating, submit_attempts, created_at, updated_at)
                      SELECT gen_random_uuid(), '%1$s', 'tpl' || g, 'm_' || g, 'en', 'UTILITY', 'Clinic', 'Hi {{1}}', 1, 'approved', 'GREEN', 0, now(), now()
                      FROM generate_series(1, 20) g;
                    INSERT INTO whatsapp_messages (id, tenant_id, patient_id, to_phone, template_name, language, params, status, wamid, attempts, created_at, updated_at)
                      SELECT gen_random_uuid(), '%1$s', gen_random_uuid(), '+9190000' || g, 'tpl1', 'en', '["x"]'::jsonb,
                        (ARRAY['sent','delivered','read','failed','blocked'])[1 + g %% 5], 'wamid' || g, 1, now(), now()
                      FROM generate_series(1, 100000) g;
                    INSERT INTO whatsapp_inbound_events (tenant_id, source, dedupe_key, field, status, attempts, received_at)
                      SELECT '%1$s', 'meta_whatsapp', 'k' || g, 'messages', 'done', 0, now() FROM generate_series(1, 300) g;
                    -- another tenant's rows must not be copied
                    INSERT INTO whatsapp_messages (id, tenant_id, to_phone, template_name, language, params, status, attempts, created_at, updated_at)
                      VALUES (gen_random_uuid(), '%2$s', '+911', 't', 'en', '[]', 'sent', 0, now(), now());
                    """.formatted(tA, tB));
        }
        var core = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl().replace("/comms_test", "/core_src"), PG.getUsername(), PG.getPassword()));
        var comms = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), "comms_app", "x"));
        var job = new TenantBackfill(core, comms, s -> s == null ? new byte[0] : s.getBytes(), s -> s.getBytes());
        long t0 = System.nanoTime();
        assertThat(job.run(tA)).as("checksums match").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("< 15 min").isLessThan(Duration.ofMinutes(15));
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ?", Integer.class, tA)).isEqualTo(100000);
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ?", Integer.class, tB)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE tenant_id = ?", Integer.class, tA)).isEqualTo(20);
        assertThat(su.queryForObject("SELECT count(*) FROM inbound_events WHERE tenant_id = ?", Integer.class, tA)).isEqualTo(300);
        assertThat(su.queryForObject("SELECT string_agg(source_table || ':' || status || ':' || source_rows, ',' ORDER BY source_table) FROM backfill_markers WHERE tenant_id = ?",
                String.class, tA)).isEqualTo("whatsapp_inbound_events:done:300,whatsapp_messages:done:100000,whatsapp_templates:done:20");
        // idempotent: a finished marker is a no-op, and a re-run from scratch inserts nothing twice
        assertThat(job.run(tA)).isTrue();
        su.update("UPDATE backfill_markers SET status = 'running', last_key = NULL, rows_copied = 0 WHERE tenant_id = ?", tA);
        assertThat(job.run(tA)).isTrue();
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ?", Integer.class, tA)).isEqualTo(100000);
        // resumable: a marker left mid-way continues after its key and ends with equal counts
        String mid = su.queryForObject("SELECT id::text FROM outbound_messages WHERE tenant_id = ? ORDER BY id OFFSET 50000 LIMIT 1", String.class, tA);
        su.update("DELETE FROM outbound_messages WHERE tenant_id = ? AND id > ?::uuid", tA, mid);
        su.update("UPDATE backfill_markers SET status = 'running', last_key = ? WHERE tenant_id = ? AND source_table = 'whatsapp_messages'", mid, tA);
        assertThat(job.run(tA)).as("rows after the marker's key are copied").isTrue();
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ?", Integer.class, tA)).isEqualTo(100000);
    }

    // ---- COM-011 ----

    @Test
    void sharedSenderIsVisibleToAnyTenantButTenantSendersOnlyToTheirOwner() {
        UUID shared = UUID.randomUUID(), own = UUID.randomUUID();
        su.update("INSERT INTO sender_accounts (id, tenant_id, kind, provider, phone_number_id, access_token_ref, status) VALUES (?,NULL,'SHARED','meta_cloud',?,'ref/shared','active')",
                shared, "pn-" + shared);
        su.update("INSERT INTO sender_accounts (id, tenant_id, kind, provider, phone_number_id, access_token_ref, status) VALUES (?,?,'TENANT','meta_cloud',?,'ref/own','active')",
                own, tA, "pn-" + own);
        var dir = new JdbcSenderDirectory(app);
        assertThat((java.util.Optional<?>) txo(() -> { bind(tB); return dir.find(shared); })).isPresent();
        assertThat((java.util.Optional<?>) txo(() -> { bind(tA); return dir.find(own); })).isPresent();
        assertThat((java.util.Optional<?>) txo(() -> { bind(tB); return dir.find(own); })).as("tenant B cannot see A's own sender").isEmpty();
        assertThatThrownBy(() -> su.update("INSERT INTO sender_accounts (kind, provider, phone_number_id, access_token_ref, tenant_id) VALUES ('SHARED','meta_cloud','x','r',?)", tA))
                .as("SHARED needs tenant_id NULL").hasMessageContaining("check");
    }

    @Test
    void twoClinicsOnTheSharedNumberThroughTheRealRouteStore() {
        UUID sender = UUID.randomUUID();
        byte[] phone = ("hash-" + UUID.randomUUID()).getBytes();
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        var store = new JdbcRouteStore(app);
        tx.executeWithoutResult(s -> store.recordOutbound(tA, sender, phone, now));
        tx.executeWithoutResult(s -> store.recordOutbound(tB, sender, phone, now.plusSeconds(600)));
        var router = new InboundRouter(store, new ButtonTokens(new byte[32], Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        Object d = txo(() -> router.route(sender, phone, null));
        assertThat(d).isInstanceOf(InboundRouter.AskWhichClinic.class);
        assertThat(((InboundRouter.AskWhichClinic) d).tenantIds()).containsExactlyInAnyOrder(tA, tB);
        // without the router's lookup flag a tenant sees only its own route
        assertThat((Integer) txo(() -> { bind(tA); return app.queryForObject("SELECT count(*) FROM phone_routes WHERE phone_hash = ?", Integer.class, (Object) phone); }))
                .isEqualTo(1);
        // a different phone has no route
        assertThat(txo(() -> router.route(sender, "other".getBytes(), null))).isInstanceOf(InboundRouter.NoRoute.class);
    }

    static boolean txb(java.util.function.BooleanSupplier f) {
        return Boolean.TRUE.equals(tx.execute(s -> f.getAsBoolean()));
    }

    static Object txo(java.util.function.Supplier<Object> f) {
        return tx.execute(s -> f.get());
    }

    private static void bind(UUID t) {
        app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, t.toString());
    }
}
