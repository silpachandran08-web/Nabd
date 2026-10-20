package com.nabd.hms.iam.internal;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.iam.api.ActorDirectory.Actor;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA-5 (S12) PLT-033 AC3: ActorDirectory batch lookup. Statements are counted at the JDBC Connection (every
 * prepareStatement/createStatement/prepareCall), independent of which JdbcTemplate method the implementation uses.
 */
class S12QaPlt033ActorDirectoryTest extends ApiTestBase {

    @Autowired ActorDirectory directory;
    @Autowired ApplicationContext ctx;

    private List<UUID> seedStaff(UUID tenant, UUID role, int n, boolean clinician, String status, String prefix) {
        String hash = pinEncoder.encode(STAFF_PIN);
        List<UUID> ids = new ArrayList<>();
        inTenantTx(tenant, () -> {
            for (int i = 0; i < n; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                jdbc.update("INSERT INTO staff (id, tenant_id, role_id, email, name, pin_hash, status, is_clinician) VALUES (?,?,?,?,?,?,?,?)",
                        id, tenant, role, id + "@s12qa.test", prefix + " " + i, hash, status, clinician);
            }
        });
        return ids;
    }

    /** Runs work against a directory whose connection counts every statement it opens; returns [result, count]. */
    private <T> Object[] counted(UUID tenant, Function<ActorDirectory, T> work) {
        AtomicInteger statements = new AtomicInteger();
        T out = inTenantTx(tenant, () -> {
            Connection real = DataSourceUtils.getConnection(jdbc.getDataSource()); // the tx-bound connection (RLS tenant set)
            Connection spy = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                if (Set.of("prepareStatement", "createStatement", "prepareCall").contains(m.getName())) statements.incrementAndGet();
                try { return m.invoke(real, a); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
            return work.apply(new JdbcActorDirectory(new JdbcTemplate(new SingleConnectionDataSource(spy, true))));
        });
        return new Object[]{out, statements.get()};
    }

    @Test
    void fiftyActorsAcrossRolesResolveInOneStatement_unknownNullDuplicateAndForeignIdsDropped() {
        SeededTenant a = seedTenant(), b = seedTenant();
        UUID doctor = seedRole(a.id(), "Doctor", false), nurse = seedRole(a.id(), "Nurse", false), recep = seedRole(a.id(), "Receptionist", false);
        List<UUID> ids = new ArrayList<>();
        ids.addAll(seedStaff(a.id(), doctor, 20, true, "active", "Dr"));
        ids.addAll(seedStaff(a.id(), nurse, 15, true, "suspended", "Nurse"));   // suspended staff still resolve (audit history)
        ids.addAll(seedStaff(a.id(), recep, 15, false, "active", "Recep"));
        UUID foreign = seedStaff(b.id(), seedRole(b.id(), "Doctor", false), 1, true, "active", "Other").get(0);

        List<UUID> asked = new ArrayList<>(ids);
        asked.addAll(Arrays.asList(ids.get(3), null, UUID.randomUUID(), foreign));
        Object[] r = counted(a.id(), d -> d.findActors(a.id(), asked));
        @SuppressWarnings("unchecked") Map<UUID, Actor> got = (Map<UUID, Actor>) r[0];
        assertEquals(1, r[1], "one SQL statement for the batch");
        assertEquals(new HashSet<>(ids), got.keySet(), "exactly the 50 tenant-a actors");
        assertEquals(new Actor(ids.get(0), "Dr 0", "Doctor", true), got.get(ids.get(0)));
        assertEquals(new Actor(ids.get(20), "Nurse 0", "Nurse", true), got.get(ids.get(20)));
        assertEquals(new Actor(ids.get(49), "Recep 14", "Receptionist", false), got.get(ids.get(49)));

        // foreign tenant id passed explicitly while bound to tenant a: nothing leaks
        assertTrue(inTenantTx(a.id(), () -> directory.findActors(b.id(), List.of(foreign))).isEmpty());
    }

    @Test
    void fiftyActorsUnderFiveMsThroughTheSpringBean() {
        SeededTenant t = seedTenant();
        List<UUID> ids = seedStaff(t.id(), seedRole(t.id(), "Doctor", false), 50, true, "active", "Dr");
        long cold = 0, best = Long.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            int n = inTenantTx(t.id(), () -> directory.findActors(t.id(), ids)).size();
            long dt = System.nanoTime() - t0;
            if (i == 0) cold = dt;
            best = Math.min(best, dt);
            assertEquals(50, n);
        }
        System.out.printf("S12QA PLT-033 findActors(50): cold %d us, best %d us (incl. tx begin/commit)%n", cold / 1000, best / 1000);
        assertTrue(best < 5_000_000L, "best " + best / 1000 + " us");
    }

    @Test
    void singleLookupAndClinicianListAreTenantIsolated() {
        SeededTenant a = seedTenant(), b = seedTenant();
        UUID ra = seedRole(a.id(), "Doctor", false), rb = seedRole(b.id(), "Doctor", false);
        List<UUID> aDocs = seedStaff(a.id(), ra, 2, true, "active", "A");
        seedStaff(a.id(), ra, 1, false, "active", "A-nonclin");
        List<UUID> bDocs = seedStaff(b.id(), rb, 3, true, "active", "B");

        List<UUID> listed = inTenantTx(a.id(), () -> directory.listClinicians(a.id())).stream().map(Actor::id).toList();
        assertEquals(new HashSet<>(aDocs), new HashSet<>(listed), "only tenant a's active clinicians");
        assertTrue(inTenantTx(a.id(), () -> directory.listClinicians(b.id())).isEmpty(), "asking for b under a's tenant context returns nothing");
        assertTrue(inTenantTx(a.id(), () -> directory.findActor(a.id(), bDocs.get(0))).isEmpty());
        Object[] one = counted(a.id(), d -> d.findActor(a.id(), aDocs.get(0)));
        assertEquals(1, one[1]);
    }

    @Test
    void exactlyOneActorDirectoryBeanAndItIsTheIamImplementation() {
        Map<String, ActorDirectory> beans = ctx.getBeansOfType(ActorDirectory.class);
        assertEquals(1, beans.size(), beans.keySet().toString());
        assertTrue(beans.values().iterator().next().getClass().getName().startsWith("com.nabd.hms.iam.internal."));
    }
}
