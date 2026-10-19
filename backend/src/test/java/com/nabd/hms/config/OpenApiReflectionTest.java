package com.nabd.hms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-034: no Docker. Walks every @RestController by reflection and checks the committed spec (and the hand-written
 * api/openapi.yaml) against it: nothing implemented is missing, nothing documented is phantom.
 */
class OpenApiReflectionTest {

    static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");

    static Set<String> implemented() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> ops = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/test-classes/")) continue; // test-only controllers (e.g. Ops003HttpS9QaTest) are not API
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String[] bases = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] own = rm.path().length == 0 ? new String[] {""} : rm.path();
                for (String b : bases) for (String o : own) for (var verb : rm.method())
                    ops.add(verb.name().toLowerCase() + " " + (b + o).replaceAll("\\{([^}:]+):[^}]*}", "{$1}"));
            }
        }
        return ops;
    }

    @SuppressWarnings("unchecked")
    static Set<String> documented(String file, String prefix) throws Exception {
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of(file)))).get("paths");
        Set<String> ops = new TreeSet<>();
        paths.forEach((p, item) -> item.keySet().stream().filter(VERBS::contains).forEach(v -> ops.add(v + " " + prefix + p)));
        return ops;
    }

    @Test
    void committedCoreSpecHasEveryImplementedOperationAndNothingElse() throws Exception {
        Set<String> impl = implemented();
        assertTrue(impl.size() >= 264, "reflection found only " + impl.size());
        assertEquals(impl, documented("../api/openapi/core.yaml", ""));
    }

    /** Known drift in api/openapi.yaml, all present since the first commit (before Sprint 10); core.yaml is the source of truth.
     * Remove an entry when its owning story lands or the spec line is fixed; a NEW phantom still fails the test. */
    static final Set<String> KNOWN_PRE_S10_PHANTOMS = Set.of(
            "get /v1/.well-known/jwks.json",       // implemented as /.well-known/jwks.json (no /v1): path typo in the hand-written spec (PLT-001 JwksController)
            "post /v1/auth/pin-reset/request",     // implemented as POST /v1/auth/pin/reset-request (AuthController, PLT-001)
            "post /v1/auth/pin-reset/confirm",     // implemented as POST /v1/auth/pin/reset-confirm (AuthController, PLT-001)
            "get /v1/audit-events",                // audit read API not built yet (platform audit-viewer story, unscheduled)
            "get /v1/tenants/{id}",                // tenant/branch admin API not built yet (platform tenant-admin story, unscheduled)
            "get /v1/tenants/{id}/branches",
            "post /v1/tenants/{id}/branches");

    @Test
    void handWrittenSpecHasNoPhantomOperations() throws Exception {
        Set<String> phantom = new TreeSet<>(documented("../api/openapi.yaml", "/v1"));
        phantom.removeAll(implemented());
        phantom.removeAll(KNOWN_PRE_S10_PHANTOMS);
        assertTrue(phantom.isEmpty(), "documented but not implemented: " + phantom);
    }
}
