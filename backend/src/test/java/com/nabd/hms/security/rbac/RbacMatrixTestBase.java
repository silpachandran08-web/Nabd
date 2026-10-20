package com.nabd.hms.security.rbac;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-042: authorization tests generated from docs/security/rbac-matrix.csv (the single source) against every mapped
 * operation's real @PreAuthorize check. Each (role, operation) pair is one generated case; the case list is committed
 * (docs/security/rbac-generated-*.csv) so a matrix or permission change shows up in the PR diff, and a stale list fails.
 */
abstract class RbacMatrixTestBase extends ApiTestBase {

    static final Path MATRIX = Path.of("../docs/security/rbac-matrix.csv");
    /** Detail written only by SecurityConfig.forbidden: a 403 from the permission layer, not a business-rule 403. */
    static final String SECURITY_403 = "You do not have permission to do this.";
    private static final Pattern AUTHORITY_CALL = Pattern.compile("has(?:Any)?Authority\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    static final String ALLOW = "allow", DENY_403 = "deny-403", DENY_401 = "deny-401";

    record Row(String plane, String role, String module, List<String> actions) {
    }

    record Op(HttpMethod method, String pattern, Class<?> beanType, Method javaMethod, Set<String> anyOf) {
        String label() {
            return method + " " + pattern;
        }

        /** Operator tokens open only these paths, staff tokens everything else (AccessTokenDecoderConfig audience rule). */
        String plane() {
            return pattern.startsWith("/v1/platform/") || pattern.startsWith("/internal/v1/") ? "operator" : "clinic";
        }
    }

    record Case(String role, Op op, String expected) {
        String line() {
            return role + "," + op.label() + "," + String.join("|", op.anyOf()) + "," + expected;
        }
    }

    record Result(int status, String body) {
    }

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired ApplicationContext ctx;
    @Autowired @Qualifier("jwtDecoder") JwtDecoder signedDecoder;
    @Autowired Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

    // ── matrix ───────────────────────────────────────────────────────────

    static List<Row> matrix() {
        try {
            return parse(Files.readAllLines(MATRIX));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Row> parse(List<String> lines) {
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("plane,")) continue;
            String[] c = line.split(",", -1);
            if (c.length != 4) throw new IllegalArgumentException("bad matrix row: " + line);
            rows.add(new Row(c[0], c[1], c[2], c[3].isEmpty() ? List.of() : List.of(c[3].split("\\|"))));
        }
        return rows;
    }

    /** role -> granted permissions (module:action), roles in matrix order. */
    static Map<String, Set<String>> permissions(List<Row> rows, String plane) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.plane().equals(plane)) continue;
            Set<String> p = out.computeIfAbsent(r.role(), k -> new TreeSet<>());
            r.actions().forEach(a -> p.add(r.module() + ":" + a));
        }
        return out;
    }

    /** Clinic rows of one role as ModuleGrant fixtures (what roles.grants stores). */
    static List<ModuleGrant> grants(List<Row> rows, String role) {
        List<ModuleGrant> out = new ArrayList<>();
        for (Row r : rows) {
            if (!r.plane().equals("clinic") || !r.role().equals(role)) continue;
            List<String> a = r.actions();
            out.add(new ModuleGrant(r.module(), a.contains("view"), a.contains("create"), a.contains("edit"), a.contains("delete"),
                    a.contains("approve"), a.contains("refundDiscount"), a.contains("export"), a.contains("breakGlass")));
        }
        return out;
    }

    // ── operations and their real permission checks ─────────────────────

    /** Every /v1 and /internal/v1 operation with its effective @PreAuthorize (method level, else class level). */
    List<Op> operations(boolean onlyPermissioned) {
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var paths = e.getKey().getPathPatternsCondition().getPatternValues();
            HandlerMethod h = e.getValue();
            if (paths.isEmpty() || !h.getBeanType().getPackageName().startsWith("com.nabd.hms")) continue;
            String path = paths.iterator().next();
            if (!path.startsWith("/v1/") && !path.startsWith("/internal/v1/")) continue;
            var methods = e.getKey().getMethodsCondition().getMethods();
            HttpMethod m = methods.isEmpty() ? HttpMethod.GET : HttpMethod.valueOf(methods.iterator().next().name());
            PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), PreAuthorize.class);
            if (pa == null) pa = AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), PreAuthorize.class);
            Set<String> anyOf = pa == null ? Set.of() : requiredAuthorities(pa.value());
            if (onlyPermissioned && anyOf.isEmpty()) continue;
            ops.add(new Op(m, path, h.getBeanType(), h.getMethod(), anyOf));
        }
        ops.sort(Comparator.comparing(Op::label));
        return ops;
    }

    /**
     * Authorities any one of which passes the expression. Expressions are hasAuthority / hasAnyAuthority, or-ed; the one
     * "or authentication.name == #id" self clause (ScheduleController) never matches a random id, so it adds nothing.
     */
    static Set<String> requiredAuthorities(String expression) {
        if (expression.contains(" and ") || expression.contains("&&") || expression.contains("!")) {
            throw new IllegalStateException("OPS-042 cannot model this @PreAuthorize yet: " + expression);
        }
        Set<String> out = new TreeSet<>();
        Matcher m = AUTHORITY_CALL.matcher(expression);
        while (m.find()) {
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find()) out.add(q.group(1));
        }
        return out;
    }

    static List<Case> cases(Map<String, Set<String>> rolePermissions, String rolePlane, List<Op> ops) {
        List<Case> out = new ArrayList<>();
        rolePermissions.forEach((role, perms) -> {
            for (Op op : ops) {
                String expected = !op.plane().equals(rolePlane) ? DENY_401
                        : op.anyOf().stream().anyMatch(perms::contains) ? ALLOW : DENY_403;
                out.add(new Case(role, op, expected));
            }
        });
        return out;
    }

    /** The committed case list must equal what the matrix and the code generate now; -Drbac.write=true rewrites it. */
    static void assertCommittedOrWrite(Path file, String what, List<Case> cases) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# GENERATED by " + what + " from docs/security/rbac-matrix.csv and the endpoints' @PreAuthorize checks. Never hand-edit;");
        lines.add("# regenerate: mvn test -Dtest=RbacMatrixApiTest,OperatorMatrixApiTest -Drbac.write=true  (deny-401 = token of the other plane)");
        lines.add("role,operation,required_any_of,expected");
        cases.forEach(c -> lines.add(c.line()));
        if (Boolean.getBoolean("rbac.write")) {
            Files.write(file, lines);
            return;
        }
        List<String> committed = Files.exists(file) ? Files.readAllLines(file) : List.of();
        List<String> added = lines.stream().filter(l -> !committed.contains(l)).limit(20).toList();
        List<String> removed = committed.stream().filter(l -> !lines.contains(l)).limit(20).toList();
        assertThat(committed).as("%s is stale (rerun with -Drbac.write=true and commit it).%n+ %s%n- %s", file, added, removed)
                .containsExactlyElementsOf(lines);
    }

    // ── calling the real endpoints ──────────────────────────────────────

    Result call(Op op, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        String path = op.pattern().replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
        ResponseEntity<String> r = http.exchange(url(path), op.method(), new HttpEntity<>("{}", h), String.class);
        return new Result(r.getStatusCode().value(), r.getBody() == null ? "" : r.getBody());
    }

    /**
     * Spring validates @RequestBody / required params before method security runs, so a denied caller sending "{}" can
     * get 400 first. Then the decision comes from the same controller proxy (its real @PreAuthorize interceptor) called
     * with the caller's real token: AccessDeniedException = denied.
     */
    boolean proxyDenies(Op op, String token) {
        var auth = jwtAuthenticationConverter.convert(signedDecoder.decode(token));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            Method m = op.javaMethod();
            m.setAccessible(true);
            Object[] args = new Object[m.getParameterCount()];
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < args.length; i++) args[i] = dummy(types[i]);
            m.invoke(ctx.getBean(op.beanType()), args);
            return false;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof AccessDeniedException;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Object dummy(Class<?> t) {
        if (t == UUID.class) return UUID.randomUUID();
        if (t == String.class) return "x";
        if (t == boolean.class) return false;
        if (t == int.class) return 0; // separate ifs: a ?: chain would promote every number to the widest type
        if (t == long.class) return 0L;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == char.class) return 'x';
        return null;
    }

    /** null when the observed outcome matches the expectation, else why not. */
    String verdict(Case c, Result r, String token) {
        boolean security403 = r.status() == 403 && r.body().contains(SECURITY_403);
        return switch (c.expected()) {
            case ALLOW -> r.status() == 401 || security403 ? "granted by the matrix but refused: HTTP " + r.status() + " " + r.body() : null;
            case DENY_401 -> r.status() == 401 ? null : "other-plane token not refused with 401: HTTP " + r.status();
            default -> security403 ? null
                    : (r.status() == 400 || r.status() == 415) && proxyDenies(c.op(), token) ? null
                    : "not granted by the matrix but not refused with 403: HTTP " + r.status() + " " + r.body();
        };
    }

    /** Calls every case (parallel by role), then one dynamic test per case. */
    List<DynamicTest> run(List<Case> cases, Map<String, String> tokens) {
        Map<Case, Result> results = new ConcurrentHashMap<>();
        tokens.keySet().parallelStream().forEach(role -> cases.stream().filter(c -> c.role().equals(role))
                .forEach(c -> results.put(c, call(c.op(), tokens.get(role)))));
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : cases) {
            tests.add(DynamicTest.dynamicTest(c.role() + " -> " + c.op().label() + " = " + c.expected(), () -> {
                String why = verdict(c, results.get(c), tokens.get(c.role()));
                if (why != null) throw new AssertionError(c.line() + ": " + why);
            }));
        }
        return tests;
    }

    static Set<String> jwtPermissions(com.fasterxml.jackson.databind.ObjectMapper om, String token) throws IOException {
        Map<?, ?> claims = om.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        Set<String> out = new TreeSet<>();
        ((List<?>) claims.get("permissions")).forEach(p -> out.add((String) p));
        return out;
    }
}
