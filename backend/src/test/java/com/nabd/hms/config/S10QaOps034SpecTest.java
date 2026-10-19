package com.nabd.hms.config;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10) for OPS-034. Written from the story's acceptance criteria. */
class S10QaOps034SpecTest extends ApiTestBase {

    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");
    private static final Path CORE = Path.of("../api/openapi/core.yaml");

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Map<String, Object>>> paths() throws Exception {
        return (Map<String, Map<String, Map<String, Object>>>) ((Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(CORE))).get("paths");
    }

    private static Set<String> implementedOps() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> ops = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String[] bases = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] own = rm.path().length == 0 ? new String[]{""} : rm.path();
                for (String b : bases) for (String o : own) for (var v : rm.method())
                    ops.add(v.name().toLowerCase() + " " + (b + o).replaceAll("\\{([^}:]+):[^}]*}", "{$1}"));
            }
        }
        return ops;
    }

    private static List<String[]> specOps() throws Exception {
        List<String[]> out = new ArrayList<>();
        paths().forEach((p, item) -> item.forEach((v, o) -> { if (VERBS.contains(v)) out.add(new String[]{v, p}); }));
        return out;
    }

    // ---- AC1 ----

    @Test
    void ac1_everyImplementedOperationIsInTheCommittedSpec_andNothingElse_atLeast264() throws Exception {
        Set<String> impl = implementedOps();
        Set<String> spec = new TreeSet<>();
        for (String[] o : specOps()) spec.add(o[0] + " " + o[1]);
        assertThat(impl.size()).isGreaterThanOrEqualTo(264);
        Set<String> missing = new TreeSet<>(impl); missing.removeAll(spec);
        Set<String> extra = new TreeSet<>(spec); extra.removeAll(impl);
        assertThat(missing).as("implemented but undocumented").isEmpty();
        assertThat(extra).as("documented but not implemented (phantom)").isEmpty();
    }

    // ---- AC2 ----

    @Test
    void ac2_coreSpecPinResetPathsMatchTheCode_noPhantomPinResetOperations() throws Exception {
        var p = paths();
        assertThat(p).containsKeys("/v1/auth/pin/reset-request", "/v1/auth/pin/reset-confirm");
        assertThat(p).doesNotContainKeys("/v1/auth/pin-reset/request", "/v1/auth/pin-reset/confirm", "/v1/.well-known/jwks.json");
        assertThat(p).containsKey("/.well-known/jwks.json");
    }

    @Test
    // known defect (OPS-034): hand-written api/openapi.yaml still lists unimplemented operations
    @Tag("known-defect-OPS-034")
    void ac2_handWrittenOpenapiYamlNoLongerAdvertisesThePhantomOperations() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> hand = (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of("../api/openapi.yaml")));
        @SuppressWarnings("unchecked")
        Set<String> handPaths = ((Map<String, Object>) hand.get("paths")).keySet();
        Set<String> phantoms = new TreeSet<>();
        for (String bad : List.of("/auth/pin-reset/request", "/auth/pin-reset/confirm", "/.well-known/jwks.json", "/audit-events", "/tenants/{id}", "/tenants/{id}/branches"))
            if (handPaths.contains(bad)) phantoms.add(bad);
        assertThat(phantoms).as("api/openapi.yaml (still named 'source of truth' by api/README.md) lists operations the code does not implement").isEmpty();
    }

    @Test
    void ac2_specIsValidOpenApi_viaSnakeYamlStructure() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(CORE));
        assertThat((String) doc.get("openapi")).startsWith("3.");
        assertThat(doc).containsKeys("info", "paths", "components");
        String raw = Files.readString(CORE);
        assertThat(raw).doesNotContain("localhost").doesNotContain("127.0.0.1"); // generated server host must not leak
    }

    // ---- AC4 ----

    @Test
    @SuppressWarnings("unchecked")
    void ac4_everyOperationHasOperationIdTagProblemJsonAndPermission() throws Exception {
        Set<String> ids = new TreeSet<>();
        List<String> bad = new ArrayList<>();
        paths().forEach((path, item) -> item.forEach((verb, op) -> {
            if (!VERBS.contains(verb)) return;
            String w = verb + " " + path;
            Object id = op.get("operationId");
            if (!(id instanceof String s) || s.isBlank() || !ids.add(s)) bad.add("operationId " + w);
            if (!(op.get("tags") instanceof List<?> t) || t.size() != 1 || String.valueOf(t.get(0)).isBlank()) bad.add("tags " + w);
            if (!(op.get("x-required-permission") instanceof String s) || s.isBlank()) bad.add("x-required-permission " + w);
            Map<String, Map<String, Object>> responses = (Map<String, Map<String, Object>>) op.get("responses");
            Map<String, Object> def = responses == null ? null : responses.get("default");
            if (def == null || !String.valueOf(def.get("content")).contains("application/problem+json") || !String.valueOf(def.get("content")).contains("#/components/schemas/Problem")) bad.add("problem+json " + w);
        }));
        assertThat(bad).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac4_tagsAreTheModuleOfTheController() throws Exception {
        Map<String, String> tagOfController = new LinkedHashMap<>();
        paths().forEach((path, item) -> item.forEach((verb, op) -> {
            if (!VERBS.contains(verb)) return;
            String id = (String) ((Map<String, Object>) op).get("operationId");
            tagOfController.put(id, String.valueOf(((List<?>) ((Map<String, Object>) op).get("tags")).get(0)));
        }));
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> wrong = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            String module = c.getPackageName().replaceFirst("^com\\.nabd\\.hms\\.?", "");
            module = module.isEmpty() ? "core" : module.split("\\.")[0];
            for (Method m : c.getDeclaredMethods()) {
                String tag = tagOfController.get(c.getSimpleName() + "_" + m.getName());
                if (tag != null && !tag.equals(module)) wrong.add(c.getSimpleName() + "." + m.getName() + " tag " + tag + " != " + module);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac4_enumsAreStringLiteralUnions_notFreeTextNorOrdinals() throws Exception {
        Map<String, Object> doc = (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(CORE));
        Map<String, Map<String, Object>> schemas = (Map<String, Map<String, Object>>) ((Map<String, Object>) doc.get("components")).get("schemas");
        List<String> bad = new ArrayList<>();
        int enums = 0;
        for (var s : schemas.entrySet()) {
            Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) s.getValue().get("properties");
            if (props == null) continue;
            for (var p : props.entrySet()) {
                Object e = p.getValue().get("enum");
                if (e == null) continue;
                enums++;
                if (!"string".equals(p.getValue().get("type")) || ((List<?>) e).stream().anyMatch(v -> !(v instanceof String))) bad.add(s.getKey() + "." + p.getKey());
            }
        }
        assertThat(bad).isEmpty();
        // the 'Problem' schema is shared (one definition, referenced everywhere), not copied per operation
        assertThat(Files.readString(CORE).split("#/components/schemas/Problem").length).isGreaterThan(200);
    }

    // ---- x-required-permission versus what the server really enforces ----

    private static String fill(String path) {
        return path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }

    private int status(String token, String verb, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        var r = http.exchange(url(fill(path)), HttpMethod.valueOf(verb.toUpperCase()), new HttpEntity<>(verb.equals("get") || verb.equals("delete") ? null : "{}", h), String.class);
        return r.getStatusCode().value();
    }

    /** CLN-004 added operator (platform-token) endpoints under /internal/v1, like /v1/platform. */
    private static boolean staffScoped(String path) {
        return !(path.startsWith("/v1/platform") || path.startsWith("/internal/") || path.startsWith("/v1/owners") || path.startsWith("/v1/owner") || path.startsWith("/v1/webhooks") || path.startsWith("/.well-known"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void permission_noTokenGets401OnEveryNonPublicOperation() throws Exception {
        List<String> bad = new ArrayList<>();
        paths().forEach((path, item) -> item.forEach((verb, op) -> {
            if (!VERBS.contains(verb)) return;
            String perm = (String) ((Map<String, Object>) op).get("x-required-permission");
            if ("public".equals(perm)) return;
            int s = status(null, verb, path);
            if (s != 401) bad.add(verb + " " + path + " (" + perm + ") -> " + s);
        }));
        assertThat(bad).as("non-public operations answering an anonymous caller with something other than 401").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void permission_aStaffRoleWithNoGrantsIsNeverServedAnOperationThatDocumentsAPermission() throws Exception {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Nothing", false);
        String token = loginAndGetAccessToken(seedStaff(t, role, "ng" + UUID.randomUUID() + "@a.com", "+919840" + (100000 + (int) (Math.random() * 800000)), false));
        List<String> bad = new ArrayList<>();
        paths().forEach((path, item) -> item.forEach((verb, op) -> {
            if (!VERBS.contains(verb) || !staffScoped(path)) return;
            String perm = (String) ((Map<String, Object>) op).get("x-required-permission");
            if (!perm.matches("[a-z_]+:[a-zA-Z]+")) return;
            int s = status(token, verb, path);
            if (s / 100 == 2 || s / 100 == 5) bad.add(verb + " " + path + " (" + perm + ") -> " + s);
        }));
        assertThat(bad).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void permission_aRoleHoldingExactlyTheDocumentedPermissionIsNotRefusedForPermission() throws Exception {
        SeededTenant t = seedTenant();
        Map<String, List<String[]>> byPerm = new LinkedHashMap<>();
        paths().forEach((path, item) -> item.forEach((verb, op) -> {
            if (!VERBS.contains(verb) || !staffScoped(path)) return;
            String perm = (String) ((Map<String, Object>) op).get("x-required-permission");
            if (perm.matches("[a-z_]+:(view|create|edit|delete|approve|export)")) byPerm.computeIfAbsent(perm, k -> new ArrayList<>()).add(new String[]{verb, path});
        }));
        List<String> bad = new ArrayList<>();
        int i = 0;
        for (var e : byPerm.entrySet()) {
            String[] mp = e.getKey().split(":");
            ModuleGrant g = new ModuleGrant(mp[0], mp[1].equals("view"), mp[1].equals("create"), mp[1].equals("edit"), mp[1].equals("delete"), mp[1].equals("approve"), false, mp[1].equals("export"));
            UUID role = seedRole(t.id(), "only-" + e.getKey(), false, g);
            String token = loginAndGetAccessToken(seedStaff(t, role, "op" + (i++) + UUID.randomUUID() + "@a.com", "+919841" + (100000 + (int) (Math.random() * 800000)), false));
            for (String[] o : e.getValue()) {
                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth(token);
                h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                var r = http.exchange(url(fill(o[1])), HttpMethod.valueOf(o[0].toUpperCase()), new HttpEntity<>(o[0].equals("get") || o[0].equals("delete") ? null : "{}", h), String.class);
                if (r.getStatusCode().value() == 401 || (r.getStatusCode().value() == 403 && String.valueOf(r.getBody()).toLowerCase().contains("permission")))
                    bad.add(o[0] + " " + o[1] + " [" + e.getKey() + "] -> " + r.getStatusCode().value() + " " + r.getBody());
            }
        }
        assertThat(bad).isEmpty();
    }
}
