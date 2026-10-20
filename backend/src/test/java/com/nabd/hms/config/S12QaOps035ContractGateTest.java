package com.nabd.hms.config;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S12 OPS-035 AC4/AC5). Independent of the engineer's ApiContractGateTest: operations come from the RUNNING
 * application's RequestMappingHandlerMapping (not a classpath scan of @RestController), the SpEL is reduced by QA's own
 * parser, and x-required-permission is then checked at RUNTIME with real tokens: a role holding none of the spec's
 * permissions must hit the security-layer 403, a role holding the spec's module must get past it.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S12QaOps035ContractGateTest extends ApiTestBase {

    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");
    private static final Pattern CALL = Pattern.compile("has(?:Any)?Authority\\(([^)]*)\\)");
    private static final Pattern Q = Pattern.compile("'([^']+)'");
    private static final String SECURITY_403 = "You do not have permission to do this.";
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 300_000);

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    /** "get /v1/x/{}" -> x-required-permission, from api/openapi/core.yaml. */
    static Map<String, String> spec() throws Exception {
        Map<String, Object> doc = new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of("../api/openapi/core.yaml")));
        Map<String, String> out = new TreeMap<>();
        ((Map<String, Map<String, Object>>) doc.get("paths")).forEach((p, item) -> item.forEach((v, o) -> {
            if (VERBS.contains(v)) out.put(v + " " + p.replaceAll("\\{[^}]+}", "{}"), String.valueOf(((Map<String, Object>) o).get("x-required-permission")));
        }));
        return out;
    }

    record Live(String key, String path, HandlerMethod h, String pre) {
    }

    /** Every operation the running app serves (com.nabd.hms handlers), with the effective @PreAuthorize text or null. */
    Map<String, Live> live() {
        Map<String, Live> out = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (!h.getBeanType().getPackageName().startsWith("com.nabd.hms")) continue;
            if (h.getBeanType().getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue;
            PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), PreAuthorize.class);
            if (pa == null) pa = AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), PreAuthorize.class);
            for (String p : e.getKey().getPathPatternsCondition().getPatternValues()) {
                var ms = e.getKey().getMethodsCondition().getMethods();
                assertThat(ms).as("%s maps every HTTP verb: the spec cannot describe it", p).isNotEmpty();
                for (var m : ms) {
                    String k = m.name().toLowerCase() + " " + p.replaceAll("\\{[^}]+}", "{}");
                    out.put(k, new Live(k, p, h, pa == null ? null : pa.value()));
                }
            }
        }
        return out;
    }

    /** QA's reduction: a pure OR-chain of hasAuthority/hasAnyAuthority -> its authorities; anything else stays verbatim. */
    static Set<String> reduce(String pre) {
        String stripped = CALL.matcher(pre).replaceAll("X").replaceAll("\\s+or\\s+", "|").trim();
        if (!stripped.matches("X(\\|X)*")) return Set.of(pre.trim());
        Set<String> out = new TreeSet<>();
        Matcher c = CALL.matcher(pre);
        while (c.find()) {
            Matcher q = Q.matcher(c.group(1));
            while (q.find()) out.add(q.group(1));
        }
        return out;
    }

    static Set<String> specSet(String v) {
        return new TreeSet<>(Arrays.asList(v.split("\\s*\\|\\s*")));
    }

    @Test
    void ac5_runningAppAndSpecHaveTheSameOperations_undocumentedCountIsZero() throws Exception {
        Map<String, String> spec = spec();
        Map<String, Live> live = live();
        Set<String> undocumented = new TreeSet<>(live.keySet());
        undocumented.removeAll(spec.keySet());
        Set<String> phantom = new TreeSet<>(spec.keySet());
        phantom.removeAll(live.keySet());
        System.out.printf("S12QA OPS-035: live operations %d, core.yaml operations %d, undocumented %d %s, phantom %d %s%n",
                live.size(), spec.size(), undocumented.size(), undocumented, phantom.size(), phantom);
        assertThat(live.size()).isGreaterThan(250);
        assertThat(undocumented).as("undocumented operations").isEmpty();
        assertThat(phantom).as("phantom operations").isEmpty();
    }

    @Test
    void ac4_xRequiredPermissionEqualsPreAuthorizeForEveryLiveOperation() throws Exception {
        Map<String, String> spec = spec();
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (Live l : live().values()) {
            String s = spec.get(l.key());
            if (s == null) continue; // counted by ac5
            checked++;
            if (l.pre() == null) {
                if (!s.equals("public") && !s.equals("authenticated")) bad.add(l.key() + ": no @PreAuthorize but spec says " + s);
            } else if (!specSet(s).equals(reduce(l.pre()))) {
                bad.add(l.key() + ": spec " + s + " != code " + l.pre());
            }
        }
        System.out.printf("S12QA OPS-035 permission reflection (runtime mappings): %d checked, %d mismatches%n", checked, bad.size());
        assertThat(checked).isGreaterThan(250);
        assertThat(bad).as("mismatches").isEmpty();
    }

    @Test
    void qaReducerNeverFlattensAndOrNegation() {
        assertThat(reduce("hasAuthority('a:view') or hasAnyAuthority('b:edit','c:edit')")).containsExactly("a:view", "b:edit", "c:edit");
        assertThat(reduce("hasAuthority('a:view') and hasAuthority('b:edit')")).containsExactly("hasAuthority('a:view') and hasAuthority('b:edit')");
        assertThat(reduce("!hasAuthority('a:view')")).hasSize(1).first().asString().startsWith("!");
    }

    @Test
    void authenticatedOnlyOperationsRefuseAnonymousCallersWith401() throws Exception {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, String> e : spec().entrySet()) {
            if (!e.getValue().equals("authenticated")) continue;
            n++;
            String[] vp = e.getKey().split(" ", 2);
            ResponseEntity<String> r = http.exchange(url(vp[1].replace("{}", UUID.randomUUID().toString())), HttpMethod.valueOf(vp[0].toUpperCase()),
                    new HttpEntity<>("{}", jsonHeaders()), String.class);
            if (r.getStatusCode().value() != 401) bad.add(e.getKey() + " -> " + r.getStatusCode().value());
        }
        assertThat(n).isGreaterThan(5);
        assertThat(bad).isEmpty();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json");
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /**
     * Runtime meaning of x-required-permission on every clinic-plane GET (no body, so validation cannot answer first):
     * a staff role whose only grant is an unrelated module gets the security-layer 403; a role with a full grant on the
     * spec's module gets past the security layer.
     */
    @Test
    void ac4_runtime_specPermissionIsNecessaryAndItsModuleSufficientOnEveryClinicGet() throws Exception {
        Map<String, String> gets = new LinkedHashMap<>();
        spec().forEach((k, v) -> {
            if (k.startsWith("get /v1/") && !k.startsWith("get /v1/platform/") && !k.startsWith("get /v1/owners/") && v.contains(":")
                    && !v.contains("(")) gets.put(k, v);
        });
        Set<String> modules = new TreeSet<>();
        gets.values().forEach(v -> specSet(v).forEach(p -> modules.add(p.substring(0, p.indexOf(':')))));
        SeededTenant t = seedTenant();
        Map<String, String> tokenByModule = new LinkedHashMap<>();
        for (String m : modules) {
            UUID role = seedRole(t.id(), "QaOnly " + m + " " + SEQ.incrementAndGet(), false,
                    new ModuleGrant(m, true, true, true, true, true, true, true, true));
            tokenByModule.put(m, loginAndGetAccessToken(seedStaff(t, role, "qa4c" + SEQ.incrementAndGet() + "@q.com", "+9193" + String.format("%08d", SEQ.incrementAndGet()), false)));
        }
        List<String> bad = new ArrayList<>();
        int denyConclusive = 0;
        for (Map.Entry<String, String> e : gets.entrySet()) {
            Set<String> need = specSet(e.getValue());
            Set<String> needModules = new TreeSet<>();
            need.forEach(p -> needModules.add(p.substring(0, p.indexOf(':'))));
            String path = e.getKey().substring(4).replace("{}", UUID.randomUUID().toString());
            String other = modules.stream().filter(m -> !needModules.contains(m)).findFirst().orElseThrow();
            ResponseEntity<String> deny = http.exchange(url(path), HttpMethod.GET, authed(tokenByModule.get(other)), String.class);
            boolean sec403 = deny.getStatusCode().value() == 403 && String.valueOf(deny.getBody()).contains(SECURITY_403);
            if (sec403) denyConclusive++;
            else if (deny.getStatusCode().value() != 400) bad.add(e.getKey() + " with only " + other + " -> " + deny.getStatusCode().value() + " (spec says " + e.getValue() + ")");
            String m = needModules.iterator().next();
            ResponseEntity<String> allow = http.exchange(url(path), HttpMethod.GET, authed(tokenByModule.get(m)), String.class);
            boolean allowSec403 = allow.getStatusCode().value() == 403 && String.valueOf(allow.getBody()).contains(SECURITY_403);
            if (allow.getStatusCode().value() == 401 || allowSec403)
                bad.add(e.getKey() + " with full " + m + " -> " + allow.getStatusCode().value() + " (spec says " + e.getValue() + ")");
        }
        System.out.printf("S12QA OPS-035 runtime permission check: %d clinic GETs, %d modules, %d conclusive denials, %d problems%n",
                gets.size(), modules.size(), denyConclusive, bad.size());
        assertThat(gets.size()).isGreaterThan(60);
        assertThat(denyConclusive).isGreaterThan(gets.size() / 2);
        assertThat(bad).isEmpty();
    }
}
