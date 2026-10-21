package com.nabd.hms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-035 stage 5 gate (plain unit test, no Spring context, so it runs in seconds in stage 2 and stage 5).
 * 1. Every controller operation is in the committed spec api/openapi/core.yaml ("undocumented operation") and the spec
 *    lists nothing the code lacks ("phantom operation").
 * 2. Permission reflection (SEC-24 endpoint registry): x-required-permission equals the @PreAuthorize authority for every
 *    operation, in core.yaml and in the hand-written api/openapi.yaml wherever it documents the operation.
 */
class ApiContractGateTest {

    static final Path CORE = Path.of("../api/openapi/core.yaml");
    static final Path HAND = Path.of("../api/openapi.yaml");
    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");
    /** Only plain OR-chains of hasAuthority/hasAnyAuthority reduce to a set of authorities; anything else must be spelled out verbatim. */
    private static final Pattern OR_CHAIN = Pattern.compile("has(Any)?Authority\\('[^']+'(\\s*,\\s*'[^']+')*\\)(\\s+or\\s+has(Any)?Authority\\('[^']+'(\\s*,\\s*'[^']+')*\\))*");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    record Op(String key, String where, Set<String> permission) {}

    /** "get /v1/x/{id}" -> operation, reflected from the main (not test) controllers. */
    static Map<String, Op> implemented() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Op> ops = new TreeMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String[] bases = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                Set<String> perm = permission(c, m);
                String[] own = rm.path().length == 0 ? new String[]{""} : rm.path();
                for (String b : bases) for (String o : own) for (var v : rm.method()) {
                    String key = v.name().toLowerCase() + " " + (b + o).replaceAll("\\{([^}:]+):[^}]*}", "{$1}");
                    ops.put(key, new Op(key, c.getSimpleName() + "." + m.getName(), perm));
                }
            }
        }
        return ops;
    }

    /** The authority the code enforces at the security layer, as a set ("public" / "authenticated" when there is no @PreAuthorize). */
    static Set<String> permission(Class<?> c, Method m) {
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
        if (pre == null) pre = AnnotatedElementUtils.findMergedAnnotation(c, PreAuthorize.class);
        if (pre == null) return Set.of(OpenApiConfig.isPublic(c, m) ? "public" : "authenticated");
        String expr = pre.value().trim();
        if (!OR_CHAIN.matcher(expr).matches()) return Set.of(expr);
        Set<String> out = new TreeSet<>();
        Matcher q = QUOTED.matcher(expr);
        while (q.find()) out.add(q.group(1));
        return out;
    }

    static Set<String> specPermission(Object v) {
        return v instanceof String s ? new TreeSet<>(Arrays.asList(s.split("\\s*\\|\\s*"))) : Set.of("<missing>");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> load(Path p) throws Exception {
        return (Map<String, Map<String, Object>>) ((Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(p))).get("paths");
    }

    /** core.yaml operations, "get /v1/x/{id}" -> x-required-permission. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> coreOps() throws Exception {
        Map<String, Object> out = new TreeMap<>();
        load(CORE).forEach((path, item) -> item.forEach((v, o) -> {
            if (VERBS.contains(v)) out.put(v + " " + path, ((Map<String, Object>) o).get("x-required-permission"));
        }));
        return out;
    }

    /** api/openapi.yaml operations keyed with param names blanked ("get /v1/x/{}"), paths made absolute from the (path-level) server URL. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> handOps() throws Exception {
        Map<String, Object> out = new TreeMap<>();
        load(HAND).forEach((path, item) -> {
            String prefix = "/v1";
            if (item.get("servers") instanceof List<?> s && !s.isEmpty()) prefix = URI.create(String.valueOf(((Map<String, Object>) s.get(0)).get("url"))).getPath().replaceAll("/$", "");
            for (var e : item.entrySet())
                if (VERBS.contains(e.getKey())) out.put(blank(e.getKey() + " " + prefix + path), ((Map<String, Object>) e.getValue()).get("x-required-permission"));
        });
        return out;
    }

    static String blank(String key) { return key.replaceAll("\\{[^}]+}", "{}"); }

    @Test
    void stage5_noUndocumentedAndNoPhantomOperations() throws Exception {
        Map<String, Op> impl = implemented();
        Map<String, Object> spec = coreOps();
        List<String> bad = new ArrayList<>();
        for (Op o : impl.values()) if (!spec.containsKey(o.key())) bad.add("undocumented operation: " + o.key() + " (" + o.where() + ")");
        for (String k : spec.keySet()) if (!impl.containsKey(k)) bad.add("phantom operation: " + k + " (in api/openapi/core.yaml, not in code)");
        long undocumented = bad.stream().filter(s -> s.startsWith("undocumented")).count();
        System.out.printf("OPS-035 api contract: %d operations in code, %d in api/openapi/core.yaml, undocumented operations: %d, phantom operations: %d%n",
                impl.size(), spec.size(), undocumented, bad.size() - undocumented);
        assertTrue(impl.size() >= 264, "controller scan found only " + impl.size() + " operations");
        assertTrue(bad.isEmpty(), String.join("\n", bad) + "\nAdd the operation to the spec (regenerate: mvn test -Dtest=OpenApiSpecApiTest -Dopenapi.write=true) and commit it.");
    }

    @Test
    void permissionReflection_specEqualsPreAuthorize_zeroMismatches() throws Exception {
        Map<String, Op> impl = implemented();
        Map<String, Object> core = coreOps();
        Map<String, Object> hand = handOps();
        List<String> bad = new ArrayList<>();
        int handCovered = 0;
        for (Op o : impl.values()) {
            if (core.containsKey(o.key()) && !specPermission(core.get(o.key())).equals(o.permission()))
                bad.add("api/openapi/core.yaml " + o.key() + ": x-required-permission " + core.get(o.key()) + " != code " + o.permission() + " (" + o.where() + ")");
            String hk = blank(o.key());
            if (hand.containsKey(hk)) {
                handCovered++;
                if (!specPermission(hand.get(hk)).equals(o.permission()))
                    bad.add("api/openapi.yaml " + o.key() + ": x-required-permission " + hand.get(hk) + " != code " + o.permission() + " (" + o.where() + ")");
            }
        }
        Set<String> implBlank = new TreeSet<>();
        impl.keySet().forEach(k -> implBlank.add(blank(k)));
        Set<String> handPhantoms = new TreeSet<>(hand.keySet());
        handPhantoms.removeAll(implBlank);
        System.out.printf("OPS-035 permission reflection: %d mismatches; api/openapi.yaml documents %d of %d operations; operations it lists that the code lacks: %s%n",
                bad.size(), handCovered, impl.size(), handPhantoms);
        assertTrue(bad.isEmpty(), bad.size() + " permission mismatches:\n" + String.join("\n", bad));
    }

    // the reducer itself: an AND (or any other SpEL) must never be flattened into an OR-set
    @Test
    void permissionReducer_onlyFlattensOrChains() {
        assertTrue(OR_CHAIN.matcher("hasAuthority('a:view') or hasAnyAuthority('b:edit', 'c:edit')").matches());
        assertTrue(!OR_CHAIN.matcher("hasAuthority('a:view') and hasAuthority('b:edit')").matches());
        assertTrue(!OR_CHAIN.matcher("hasRole('X')").matches());
        assertTrue(specPermission("a:view | b:edit").equals(Set.of("a:view", "b:edit")));
        assertTrue(specPermission(null).equals(Set.of("<missing>")));
    }
}
