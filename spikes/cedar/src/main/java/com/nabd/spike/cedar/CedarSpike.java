package com.nabd.spike.cedar;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IDC-026 spike harness: loads cedar-java (native library), validates policies/ against the schema with the engine itself,
 * checks expected decisions, then times decisions per entity-set size (10,000 for the 1-resource row, 2,000 for larger
 * sets). Exit 0 only when every self-check passes and the 1-resource row meets p95 &lt; 5 ms and p99 &lt; 10 ms.
 * Usage: CedarSpike &lt;policies-dir&gt; [resources per call ...]   (default 1; the full sweep is 1 10 25 50)
 */
public final class CedarSpike {
    static final int DECISIONS = 10_000, RESOURCES = 50;
    static final double P95_LIMIT_MS = 5.0, P99_LIMIT_MS = 10.0;

    static EntityUID uid(String type, String id) { return EntityTypeName.parse("Nabd::" + type).orElseThrow().of(id); }
    static final EntityUID READ = uid("Action", "readRecord"), EXPORT = uid("Action", "exportRecord"), READ_PATIENT = uid("Action", "readPatient");
    static final EntityUID F1 = uid("Facility", "f1"), F2 = uid("Facility", "f2");

    static Value set(EntityUID... ids) { return new CedarList(new ArrayList<Value>(Arrays.asList(ids))); }

    static Entity role(String id, String... parents) {
        Set<EntityUID> p = new HashSet<>();
        for (String x : parents) p.add(uid("Role", x));
        return new Entity(uid("Role", id), p);
    }
    static Entity dept(String id, String access) {
        return new Entity(uid("Department", id), Map.of("specialtyAccess", new PrimString(access)), Set.of());
    }
    static Entity staff(String id, String role, String dept, EntityUID facility, String scope, boolean suspended) {
        return new Entity(uid("Staff", id), Map.of("facility", facility, "department", uid("Department", dept),
                "scope", new PrimString(scope), "suspended", new PrimBool(suspended)), Set.of(uid("Role", role)));
    }
    static Entity patient(String id, boolean withdrawn, boolean verified, EntityUID... treatedBy) {
        return new Entity(uid("Patient", id), Map.of("consentWithdrawn", new PrimBool(withdrawn),
                "verified", new PrimBool(verified), "treatedBy", set(treatedBy)), Set.of());
    }
    static Entity record(String id, String patient, EntityUID facility, String dept, String author, String kind,
                         boolean signed, boolean sensitive, boolean authorOnly, EntityUID[] careTeam, EntityUID[] referredTo) {
        Map<String, Value> a = new HashMap<>();
        a.put("patient", uid("Patient", patient)); a.put("facility", facility); a.put("department", uid("Department", dept));
        a.put("author", uid("Staff", author)); a.put("careTeam", set(careTeam)); a.put("referredTo", set(referredTo));
        a.put("kind", new PrimString(kind)); a.put("signed", new PrimBool(signed));
        a.put("sensitive", new PrimBool(sensitive)); a.put("authorOnly", new PrimBool(authorOnly));
        return new Entity(uid("Record", id), a, Set.of());
    }
    static Map<String, Value> ctx(String mode, EntityUID breakGlassPatient, EntityUID delegatedFrom) {
        Map<String, Value> c = new HashMap<>();
        c.put("visibilityMode", new PrimString(mode)); c.put("policyVersion", new PrimLong(1));
        if (breakGlassPatient != null) c.put("breakGlassPatient", breakGlassPatient);
        if (delegatedFrom != null) c.put("delegatedFrom", delegatedFrom);
        return c;
    }
    static EntityUID[] staffIds(String... ids) { return Arrays.stream(ids).map(i -> uid("Staff", i)).toArray(EntityUID[]::new); }
    static EntityUID[] deptIds(String... ids) { return Arrays.stream(ids).map(i -> uid("Department", i)).toArray(EntityUID[]::new); }

    /** Roles, departments and staff shared by the self-check and the benchmark. */
    static List<Entity> world() {
        return List.of(role("clinician"), role("front_desk"), role("doctor", "clinician"), role("nurse", "clinician"),
                role("receptionist", "front_desk"), role("billing", "front_desk"), role("pharmacist"), role("lab_tech"),
                dept("cardiology", "open"), dept("orthopaedics", "open"), dept("psychiatry", "referral_only"), dept("staff-clinic", "break_glass_only"),
                staff("dr-cardio", "doctor", "cardiology", F1, "all_patients", false),
                staff("dr-cardio-own", "doctor", "cardiology", F1, "own_patients", false),
                staff("dr-cardio-suspended", "doctor", "cardiology", F1, "all_patients", true),
                staff("dr-other-facility", "doctor", "cardiology", F2, "all_patients", false),
                staff("dr-ortho", "doctor", "orthopaedics", F1, "all_patients", false),
                staff("nurse-ortho", "nurse", "orthopaedics", F1, "all_patients", false),
                staff("billing-1", "billing", "cardiology", F1, "all_patients", false),
                staff("reception-1", "receptionist", "cardiology", F1, "all_patients", false),
                staff("pharmacist-1", "pharmacist", "cardiology", F1, "all_patients", false));
    }

    record Check(String name, String principal, EntityUID action, EntityUID resource, Map<String, Value> context, boolean allow) { }

    static final String SAME = "SAME_DEPT_PLUS_REFERRED", TEAM = "CARE_TEAM_ONLY";

    static int selfCheck(AuthorizationEngine engine, PolicySet policies) throws Exception {
        Set<Entity> e = new HashSet<>(world());
        EntityUID[] none = {}, teamOrtho = staffIds("dr-ortho"), teamWithBilling = staffIds("dr-ortho", "billing-1");
        e.add(patient("p1", false, true, uid("Staff", "dr-ortho")));
        e.add(patient("p-own", false, true, uid("Staff", "dr-cardio-own")));
        e.add(patient("p-withdrawn", true, true));
        e.add(patient("p-unverified", false, false));
        e.add(record("cardio-note", "p1", F1, "cardiology", "dr-cardio", "note", true, false, false, none, none));
        e.add(record("cardio-note-own", "p-own", F1, "cardiology", "dr-cardio", "note", true, false, false, none, none));
        e.add(record("ortho-note", "p1", F1, "orthopaedics", "dr-ortho", "note", true, false, false, teamWithBilling, none));
        e.add(record("ortho-note-referred", "p1", F1, "orthopaedics", "dr-ortho", "note", true, false, false, teamOrtho, deptIds("cardiology")));
        e.add(record("withdrawn-note", "p-withdrawn", F1, "cardiology", "dr-cardio", "note", true, false, false, none, none));
        e.add(record("unverified-note", "p-unverified", F1, "cardiology", "dr-cardio", "note", true, false, false, none, none));
        e.add(record("cardio-sensitive", "p1", F1, "cardiology", "dr-ortho", "note", true, true, false, teamOrtho, none));
        e.add(record("cardio-draft", "p1", F1, "cardiology", "dr-ortho", "note", false, false, false, none, none));
        e.add(record("cardio-rx", "p1", F1, "cardiology", "dr-cardio", "prescription", true, false, false, none, none));
        e.add(record("psych-note", "p1", F1, "psychiatry", "dr-ortho", "note", true, false, false, teamOrtho, none));
        e.add(record("staff-clinic-note", "p1", F1, "staff-clinic", "dr-ortho", "note", true, false, false, teamOrtho, none));
        EntityUID p1 = uid("Patient", "p1"), pOwn = uid("Patient", "p-own");
        List<Check> checks = List.of(
            new Check("same department is allowed", "dr-cardio", READ, uid("Record", "cardio-note"), ctx(SAME, null, null), true),
            new Check("other department without referral is denied (IDC-029)", "dr-cardio", READ, uid("Record", "ortho-note"), ctx(SAME, null, null), false),
            new Check("other department after referral is allowed (IDC-029)", "dr-cardio", READ, uid("Record", "ortho-note-referred"), ctx(SAME, null, null), true),
            new Check("consent withdrawn is denied even for the author (IDC-001)", "dr-cardio", READ, uid("Record", "withdrawn-note"), ctx(SAME, null, null), false),
            new Check("unverified patient is denied (IDC-001)", "dr-cardio", READ, uid("Record", "unverified-note"), ctx(SAME, null, null), false),
            new Check("billing is denied even on the care team (IDC-027 floor)", "billing-1", READ, uid("Record", "ortho-note"), ctx(SAME, null, null), false),
            new Check("receptionist is denied clinical records", "reception-1", READ, uid("Record", "cardio-note"), ctx(SAME, null, null), false),
            new Check("receptionist reads demographics", "reception-1", READ_PATIENT, p1, ctx(SAME, null, null), true),
            new Check("own-patients doctor is denied another patient (IDC-001)", "dr-cardio-own", READ, uid("Record", "cardio-note"), ctx(SAME, null, null), false),
            new Check("own-patients doctor reads her own patient", "dr-cardio-own", READ, uid("Record", "cardio-note-own"), ctx(SAME, null, null), true),
            new Check("own-patients doctor is denied another patient's demographics", "dr-cardio-own", READ_PATIENT, p1, ctx(SAME, null, null), false),
            new Check("care-team-only denies a same-department non-member (IDC-029)", "dr-cardio-own", READ, uid("Record", "cardio-note-own"), ctx(TEAM, null, null), false),
            new Check("care-team-only allows a member", "dr-ortho", READ, uid("Record", "ortho-note"), ctx(TEAM, null, null), true),
            new Check("sensitive record is denied outside the care team", "dr-cardio", READ, uid("Record", "cardio-sensitive"), ctx(SAME, null, null), false),
            new Check("break-glass for this patient opens it (IDC-030)", "dr-cardio", READ, uid("Record", "cardio-sensitive"), ctx(SAME, p1, null), true),
            new Check("break-glass for another patient does not (IDC-030)", "dr-cardio", READ, uid("Record", "cardio-sensitive"), ctx(SAME, pOwn, null), false),
            new Check("suspended staff is denied", "dr-cardio-suspended", READ, uid("Record", "cardio-note"), ctx(SAME, null, null), false),
            new Check("another facility is denied", "dr-other-facility", READ, uid("Record", "cardio-note"), ctx(SAME, null, null), false),
            new Check("another author's draft is denied", "dr-cardio", READ, uid("Record", "cardio-draft"), ctx(SAME, null, null), false),
            new Check("pharmacist reads a signed prescription", "pharmacist-1", READ, uid("Record", "cardio-rx"), ctx(SAME, null, null), true),
            new Check("pharmacist is denied a note", "pharmacist-1", READ, uid("Record", "cardio-note"), ctx(SAME, null, null), false),
            new Check("delegate of a care-team doctor reads", "dr-cardio", READ, uid("Record", "ortho-note"), ctx(SAME, null, uid("Staff", "dr-ortho")), true),
            new Check("delegate is denied in a referral-only specialty (IDC-031)", "dr-cardio", READ, uid("Record", "psych-note"), ctx(SAME, null, uid("Staff", "dr-ortho")), false),
            new Check("break-glass-only specialty denies a delegate (IDC-031)", "nurse-ortho", READ, uid("Record", "staff-clinic-note"), ctx(SAME, null, uid("Staff", "dr-ortho")), false),
            new Check("break-glass-only specialty opens under break-glass", "dr-cardio", READ, uid("Record", "staff-clinic-note"), ctx(SAME, p1, null), true),
            new Check("nurse may not export", "nurse-ortho", EXPORT, uid("Record", "ortho-note"), ctx(SAME, null, null), false),
            new Check("author may export", "dr-ortho", EXPORT, uid("Record", "ortho-note"), ctx(SAME, null, null), true));
        int failed = 0;
        for (Check c : checks) {
            AuthorizationResponse r = engine.isAuthorized(new AuthorizationRequest(uid("Staff", c.principal), c.action, c.resource, c.context), policies, e);
            boolean ok = r.type == AuthorizationResponse.SuccessOrFailure.Success && r.success.get().getErrors().isEmpty()
                    && r.success.get().isAllowed() == c.allow;
            if (!ok) { failed++; System.out.println("  SELF-CHECK FAIL: " + c.name + " -> " + r); }
        }
        System.out.println("self-check: " + (checks.size() - failed) + "/" + checks.size() + " expected decisions");
        return failed;
    }

    /** One doctor reading a 50-record result list: mixed departments, patients, sensitivity, so allow and deny both occur. */
    static List<Entity> benchRecords() {
        String[] depts = {"cardiology", "cardiology", "orthopaedics", "cardiology", "psychiatry"};
        String[] kinds = {"note", "prescription", "lab_result", "vitals", "document"};
        List<Entity> out = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            out.add(patient("bp" + i, i == 7, i != 8, staffIds("dr-cardio", "dr-ortho")));
        for (int i = 0; i < RESOURCES; i++) {
            String dept = depts[i % depts.length];
            EntityUID[] team = i % 4 == 0 ? staffIds("dr-cardio", "dr-ortho", "nurse-ortho") : staffIds("dr-ortho", "nurse-ortho", "dr-cardio-own");
            out.add(record("br" + i, "bp" + (i % 10), F1, dept, i % 6 == 0 ? "dr-cardio" : "dr-ortho", kinds[i % kinds.length],
                    i % 11 != 0, i % 9 == 0, i % 18 == 0, team, i % 5 == 2 ? deptIds("cardiology") : deptIds()));
        }
        return out;
    }

    /**
     * Times `decisions` calls for one doctor walking a 50-record result list. Every call carries an entity set with
     * `resourcesPerCall` records (the one being decided plus its neighbours in the list), their patients, the principal,
     * its roles and the departments. The timed region is engine.isAuthorized only: JSON out, JNI, Rust, JSON back.
     */
    static boolean bench(String label, boolean gate, int decisions, AuthorizationEngine engine, PolicySet policies, int resourcesPerCall) throws Exception {
        Map<EntityUID, Entity> byId = new HashMap<>();
        for (Entity x : world()) byId.put(x.getEUID(), x);
        for (Entity x : benchRecords()) byId.put(x.getEUID(), x);
        AuthorizationRequest[] reqs = new AuthorizationRequest[RESOURCES];
        List<Set<Entity>> slices = new ArrayList<>();
        for (int i = 0; i < RESOURCES; i++) {
            Set<Entity> slice = new HashSet<>();
            for (String fixed : List.of("Staff:dr-cardio", "Role:doctor", "Role:clinician", "Department:cardiology", "Department:orthopaedics",
                    "Department:psychiatry", "Department:staff-clinic")) slice.add(byId.get(uid(fixed.split(":")[0], fixed.split(":")[1])));
            for (int k = 0; k < resourcesPerCall; k++) {
                Entity rec = byId.get(uid("Record", "br" + (i + k) % RESOURCES));
                slice.add(rec);
                slice.add(byId.get((EntityUID) rec.getAttr("patient")));
            }
            slices.add(slice);
            reqs[i] = new AuthorizationRequest(uid("Staff", "dr-cardio"), i % 10 == 9 ? EXPORT : READ, uid("Record", "br" + i),
                    ctx(SAME, i % 25 == 3 ? (EntityUID) byId.get(uid("Record", "br" + i)).getAttr("patient") : null, null));
        }
        int allowed = 0;
        for (int i = 0; i < decisions / 10; i++) engine.isAuthorized(reqs[i % RESOURCES], policies, slices.get(i % RESOURCES));   // warm-up
        var cpu = java.lang.management.ManagementFactory.getThreadMXBean();
        long[] ns = new long[decisions];
        long cpu0 = cpu.getCurrentThreadCpuTime();
        for (int i = 0; i < decisions; i++) {
            long t = System.nanoTime();
            AuthorizationResponse r = engine.isAuthorized(reqs[i % RESOURCES], policies, slices.get(i % RESOURCES));
            ns[i] = System.nanoTime() - t;
            if (r.type != AuthorizationResponse.SuccessOrFailure.Success || !r.success.get().getErrors().isEmpty())
                throw new IllegalStateException("decision " + i + " errored: " + r);
            if (r.success.get().isAllowed()) allowed++;
        }
        double cpuMs = (cpu.getCurrentThreadCpuTime() - cpu0) / 1e6 / decisions;   // not inflated by a busy host, unlike wall time
        // informational for IDC-027 (timeline of 200 items): consecutive windows of 200 measured decisions
        long[] batches = new long[decisions / 200];
        long total = 0;
        for (int i = 0; i < decisions; i++) { batches[i / 200] += ns[i]; total += ns[i]; }
        Arrays.sort(ns);
        Arrays.sort(batches);
        double p95 = ns[(int) Math.ceil(decisions * 0.95) - 1] / 1e6, p99 = ns[(int) Math.ceil(decisions * 0.99) - 1] / 1e6;
        boolean pass = p95 < P95_LIMIT_MS && p99 < P99_LIMIT_MS;
        System.out.printf("%s: decisions=%d resources/call=%d entities/call=%d allow=%d deny=%d | cpu/decision=%.3f ms mean=%.3f ms p50=%.3f ms p95=%.3f ms"
                        + " p99=%.3f ms max=%.3f ms | 200-decision batch p50=%.1f ms p95=%.1f ms | %s%s%n",
                label, decisions, resourcesPerCall, slices.get(0).size(), allowed, decisions - allowed, cpuMs, total / 1e6 / decisions, ns[decisions / 2] / 1e6,
                p95, p99, ns[decisions - 1] / 1e6, batches[batches.length / 2] / 1e6, batches[(int) Math.ceil(batches.length * 0.95) - 1] / 1e6,
                pass ? "PASS" : "FAIL", gate ? " (sets the exit code)" : " (reported only)");
        return pass || !gate;
    }

    static String procLine(String file, String needle) {
        try (var lines = Files.lines(Path.of(file))) {
            return lines.filter(l -> l.contains(needle)).findFirst().orElse("(none)").replaceAll("\\s+", " ");
        } catch (Exception e) { return "(not Linux)"; }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        System.out.println("java=" + Runtime.version() + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + " cpus=" + Runtime.getRuntime().availableProcessors() + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / (1 << 20)
                + " tmpdir=" + System.getProperty("java.io.tmpdir"));
        System.out.println("process " + procLine("/proc/self/status", "Uid:") + " " + procLine("/proc/self/status", "CapEff:"));
        long t0 = System.nanoTime();
        AuthorizationEngine engine = new BasicAuthorizationEngine();   // loads the native library; throws if it cannot
        System.out.println("NATIVE LIBRARY LOADED in " + (System.nanoTime() - t0) / 1_000_000 + " ms from "
                + Path.of(BasicAuthorizationEngine.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getFileName()
                + ", Cedar language " + AuthorizationEngine.getCedarLangVersion() + ", mapped: " + procLine("/proc/self/maps", "cedar_java_ffi"));

        Schema schema = Schema.parse(Schema.JsonOrCedar.Cedar, Files.readString(dir.resolve("nabd.cedarschema")));
        PolicySet policies = PolicySet.parsePolicies(dir.resolve("clinical-read.cedar"));
        int failures = 0;
        System.out.println("policies=" + policies.getNumPolicies());
        if (policies.getNumPolicies() != 30) { failures++; System.out.println("  FAIL: expected 30 policies"); }
        ValidationResponse v = engine.validate(new ValidationRequest(schema, policies));
        System.out.println("engine validation passed=" + v.validationPassed());
        if (!v.validationPassed()) { failures++; System.out.println("  FAIL: " + v); }
        failures += selfCheck(engine, policies);

        PolicySet cached = PolicySet.parsePolicies(dir.resolve("clinical-read.cedar"));
        cached.cache();   // policy set parsed once on the Rust side; this is how a PDP would hold it
        System.out.println("target per row: p95 < " + P95_LIMIT_MS + " ms and p99 < " + P99_LIMIT_MS + " ms");
        // exit code: the 1-resource row (the PEP sends only what one decision can touch), 10,000 decisions.
        // Larger entity sets cost several ms of CPU per call, so they get 2,000 decisions and are reported only.
        boolean ok = true;
        for (int i = 1; i < Math.max(args.length, 2); i++) {
            int n = args.length > 1 ? Integer.parseInt(args[i]) : 1;
            ok &= bench("cached-policies", n == 1, n == 1 ? DECISIONS : 2_000, engine, cached, n);
        }
        // policy text re-parsed on every call: slow, so fewer samples; shows why the PDP must cache the policy set
        bench("uncached-policies", false, 1_000, engine, policies, 1);
        if (failures > 0 || !ok) { System.out.println("RESULT: FAIL"); System.exit(1); }
        System.out.println("RESULT: PASS");
    }
}
