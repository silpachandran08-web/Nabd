package com.nabd.hms.iam.internal;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.iam.api.ActorDirectory.Actor;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PLT-033: ActorDirectory contract (single, batch, clinicians), tenant isolation, one query for 50 actors in < 5 ms. */
class ActorDirectoryApiTest extends ApiTestBase {

    @Autowired ActorDirectory actors;

    /** Counts JDBC queries: every JdbcTemplate query path goes through query(PreparedStatementCreator, setter, extractor). */
    static class CountingJdbc extends JdbcTemplate {
        final AtomicInteger statements = new AtomicInteger();
        CountingJdbc(javax.sql.DataSource ds) { super(ds); }
        @Override public <T> T query(PreparedStatementCreator psc, PreparedStatementSetter pss, ResultSetExtractor<T> rse) {
            statements.incrementAndGet();
            return super.query(psc, pss, rse);
        }
    }

    private List<UUID> seedStaffRows(SeededTenant t, UUID roleId, int n, boolean clinician, String status) {
        String hash = pinEncoder.encode(STAFF_PIN);
        List<UUID> ids = new ArrayList<>();
        inTenantTx(t.id(), () -> {
            for (int i = 0; i < n; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                jdbc.update("INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, is_clinician) " +
                                "VALUES (?,?,?,?,?,?,?,?,?)",
                        id, t.id(), roleId, id + "@plt033.test", String.format("Actor %03d", i), null, hash, status, clinician);
            }
        });
        return ids;
    }

    @Test
    void findActorReturnsNameRoleAndClinicianFlag_unknownAndOtherTenantAreEmpty() {
        SeededTenant a = seedTenant(), b = seedTenant();
        UUID roleA = seedRole(a.id(), "Doctor", false), roleB = seedRole(b.id(), "Doctor", false);
        UUID doc = seedStaffRows(a, roleA, 1, true, "active").get(0);
        UUID other = seedStaffRows(b, roleB, 1, false, "active").get(0);

        Actor got = inTenantTx(a.id(), () -> actors.findActor(a.id(), doc).orElseThrow());
        assertEquals(new Actor(doc, "Actor 000", "Doctor", true), got);
        assertTrue(inTenantTx(a.id(), () -> actors.findActor(a.id(), UUID.randomUUID())).isEmpty());
        assertTrue(inTenantTx(a.id(), () -> actors.findActor(a.id(), null)).isEmpty());
        // cross-tenant: b's staff id asked for under tenant a (both the predicate and RLS refuse it)
        assertTrue(inTenantTx(a.id(), () -> actors.findActor(a.id(), other)).isEmpty());
        assertTrue(inTenantTx(a.id(), () -> actors.findActors(a.id(), List.of(doc, other))).keySet().equals(java.util.Set.of(doc)));
    }

    @Test
    void findActorsResolvesFiftyActorsInOneQueryUnderFiveMs() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Receptionist", false);
        List<UUID> ids = seedStaffRows(t, role, 50, false, "active");
        CountingJdbc counting = new CountingJdbc(jdbc.getDataSource());
        JdbcActorDirectory dir = new JdbcActorDirectory(counting);

        List<UUID> asked = new ArrayList<>(ids);
        asked.add(ids.get(0));            // duplicate id
        asked.add(UUID.randomUUID());     // unknown id
        Map<UUID, Actor> found = inTenantTx(t.id(), () -> dir.findActors(t.id(), asked));
        assertEquals(1, counting.statements.get(), "one query for the whole batch (no N+1)");
        assertEquals(50, found.size());
        assertTrue(found.values().stream().allMatch(x -> "Receptionist".equals(x.role()) && !x.clinician()));
        assertEquals(ids.get(7), found.get(ids.get(7)).id());

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 7; run++) {   // warm caches / plan, then take the best of several runs (shared CI box)
            long t0 = System.nanoTime();
            inTenantTx(t.id(), () -> dir.findActors(t.id(), ids));
            best = Math.min(best, System.nanoTime() - t0);
        }
        assertTrue(best < 5_000_000L, "findActors(50) took " + best / 1000 + " us");

        counting.statements.set(0);
        assertTrue(inTenantTx(t.id(), () -> dir.findActors(t.id(), List.of())).isEmpty());
        assertEquals(0, counting.statements.get(), "empty batch issues no query");
    }

    @Test
    void listCliniciansReturnsOnlyActiveClinicians() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Doctor", false);
        List<UUID> active = seedStaffRows(t, role, 2, true, "active");
        seedStaffRows(t, role, 1, true, "suspended");
        seedStaffRows(t, role, 1, false, "active");
        List<Actor> got = inTenantTx(t.id(), () -> actors.listClinicians(t.id()));
        assertEquals(active.stream().sorted().toList(), got.stream().map(Actor::id).sorted().toList());
        assertFalse(got.stream().anyMatch(x -> !x.clinician()));
    }
}
