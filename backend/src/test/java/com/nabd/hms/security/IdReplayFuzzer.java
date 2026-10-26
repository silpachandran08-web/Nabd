package com.nabd.hms.security;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** OPS-043: spec-driven cross-tenant id-replay. Under tenant A's credentials every operation of the OpenAPI spec is
 * replayed with tenant B's ids in path, query and body. Findings: (1) a 2xx whose body holds any B id, (2) a 2xx when a B id
 * sat in the path, (3) a row of tenant A in the database that references a B id (superuser scan), (4) B's row counts changed.
 * Synthetic data only; findings carry method, path, status and a trimmed request/response, never real PHI. */
public final class IdReplayFuzzer {

    public record Finding(String rule, String method, String path, int status, String request, String response) {
    }

    private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final List<String> SKIP_PREFIXES = List.of("/v1/auth/", "/v1/webhooks", "/v1/events", "/v1/platform", "/v1/owners", "/internal");

    private final Map<String, Object> spec;
    private final String base;
    private final String tokenA;
    private final Map<String, List<String>> bIdsByTable;
    private final Set<String> bIds = new HashSet<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public final Map<String, Integer> statusClasses = new TreeMap<>();
    public int requests;

    public IdReplayFuzzer(Map<String, Object> spec, String base, String tokenA, Map<String, List<String>> bIdsByTable) {
        this.spec = spec;
        this.base = base;
        this.tokenA = tokenA;
        this.bIdsByTable = bIdsByTable;
        bIdsByTable.values().forEach(l -> l.forEach(i -> bIds.add(i.toLowerCase())));
    }

    @SuppressWarnings("unchecked")
    public List<Finding> run() throws Exception {
        List<Finding> out = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) spec.getOrDefault("paths", Map.of());
        for (var pe : paths.entrySet()) {
            if (SKIP_PREFIXES.stream().anyMatch(pe.getKey()::startsWith)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) pe.getValue();
            for (var oe : item.entrySet()) {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(oe.getKey())) {
                    continue;
                }
                Map<String, Object> op = (Map<String, Object>) oe.getValue();
                List<Map<String, Object>> params = new ArrayList<>();
                params.addAll((List<Map<String, Object>>) item.getOrDefault("parameters", List.of()));
                params.addAll((List<Map<String, Object>>) op.getOrDefault("parameters", List.of()));
                params.replaceAll(p -> (Map<String, Object>) resolve(p));
                for (int variant = 0; variant < 3; variant++) {
                    replay(out, pe.getKey(), oe.getKey().toUpperCase(), op, params, variant);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void replay(List<Finding> out, String path, String method, Map<String, Object> op, List<Map<String, Object>> params, int variant) throws Exception {
        String url = path;
        boolean pathId = false;
        StringBuilder query = new StringBuilder();
        for (Map<String, Object> p : params) {
            String name = (String) p.get("name");
            Map<String, Object> schema = (Map<String, Object>) resolve(p.getOrDefault("schema", Map.of()));
            if ("path".equals(p.get("in"))) {
                String v = idFor(name, variant);
                if (isIdLike(name, schema) && v != null) {
                    pathId = true;
                } else {
                    v = sample(name, schema);
                }
                url = url.replace("{" + name + "}", URLEncoder.encode(v, StandardCharsets.UTF_8));
            } else if ("query".equals(p.get("in"))) {
                String v = isIdLike(name, schema) ? idFor(name, variant) : null;
                if (v != null || Boolean.TRUE.equals(p.get("required")) || name.equals("limit")) {
                    query.append(query.length() == 0 ? '?' : '&').append(name).append('=')
                            .append(URLEncoder.encode(v != null ? v : name.equals("limit") ? "100" : sample(name, schema), StandardCharsets.UTF_8));
                }
            }
        }
        String body = null;
        Map<String, Object> rb = (Map<String, Object>) resolve(op.get("requestBody"));
        if (rb != null) {
            Map<String, Object> content = (Map<String, Object>) rb.getOrDefault("content", Map.of());
            Map<String, Object> json = (Map<String, Object>) content.get("application/json");
            if (json != null) {
                body = toJson(bodyValue("", (Map<String, Object>) json.getOrDefault("schema", Map.of()), variant, 0));
            }
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + url + query)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + tokenA).header("Accept", "application/json");
        if (!method.equals("GET") && !method.equals("DELETE")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            return; // a refused connection is not a leak; the run-level assertion on request count catches a dead server
        }
        requests++;
        statusClasses.merge(r.statusCode() / 100 + "xx", 1, Integer::sum);
        if (r.statusCode() / 100 != 2) {
            return;
        }
        String leaked = firstBId(r.body());
        String reqText = trim(method + " " + url + query + (body == null ? "" : " " + body));
        if (leaked != null) {
            out.add(new Finding("B_ID_IN_2XX_BODY", method, path, r.statusCode(), reqText, trim(r.body())));
        } else if (pathId && !method.equals("GET")) { // a GET on an unknown id may answer 200 with an empty list; a write must be refused
            out.add(new Finding("2XX_ON_FOREIGN_PATH_ID", method, path, r.statusCode(), reqText, trim(r.body())));
        }
    }

    private String firstBId(String text) {
        if (text == null) {
            return null;
        }
        var m = UUID_RE.matcher(text);
        while (m.find()) {
            if (bIds.contains(m.group().toLowerCase())) {
                return m.group();
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }

    private static boolean isIdLike(String name, Map<String, Object> schema) {
        return "uuid".equals(schema.get("format")) || name.endsWith("Id") || name.endsWith("Ids") || name.equals("id");
    }

    /** A B id for a parameter or property name: the table named like it (patientId -> patients) else a spread across B's tables. */
    private String idFor(String name, int variant) {
        String stem = name.replaceAll("Ids?$", "").replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        for (String t : new String[]{stem, stem + "s", stem + "es", stem.replaceAll("y$", "ies")}) {
            List<String> l = bIdsByTable.get(t);
            if (l != null && !l.isEmpty()) {
                return l.get(variant % l.size());
            }
        }
        List<String> flat = bIdsByTable.values().stream().flatMap(List::stream).sorted().toList();
        return flat.isEmpty() ? null : flat.get((variant * 7) % flat.size());
    }

    @SuppressWarnings("unchecked")
    Object bodyValue(String name, Map<String, Object> raw, int variant, int depth) {
        Map<String, Object> s = (Map<String, Object>) resolve(raw);
        if (s.containsKey("allOf")) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Object o : (List<Object>) s.get("allOf")) {
                Map<String, Object> part = (Map<String, Object>) resolve(o);
                Map<String, Object> props = (Map<String, Object>) part.getOrDefault("properties", Map.of());
                ((Map<String, Object>) merged.computeIfAbsent("properties", k -> new LinkedHashMap<>())).putAll(props);
            }
            merged.put("type", "object");
            s = merged;
        }
        for (String k : new String[]{"oneOf", "anyOf"}) {
            if (s.get(k) instanceof List<?> l && !l.isEmpty()) {
                return bodyValue(name, (Map<String, Object>) l.get(0), variant, depth);
            }
        }
        String type = (String) s.getOrDefault("type", s.containsKey("properties") ? "object" : "string");
        if (s.get("enum") instanceof List<?> e && !e.isEmpty()) {
            return e.get(0);
        }
        switch (type) {
            case "object" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                if (depth > 3) {
                    return m;
                }
                for (var pe : ((Map<String, Object>) s.getOrDefault("properties", Map.of())).entrySet()) {
                    m.put(pe.getKey(), bodyValue(pe.getKey(), (Map<String, Object>) pe.getValue(), variant, depth + 1));
                }
                return m;
            }
            case "array" -> {
                Map<String, Object> items = (Map<String, Object>) s.getOrDefault("items", Map.of());
                return depth > 3 ? List.of() : List.of(bodyValue(name, items, variant, depth + 1));
            }
            case "integer", "number" -> {
                Object min = s.get("minimum");
                return min instanceof Number n ? n : 1;
            }
            case "boolean" -> {
                return false;
            }
            default -> {
                if (isIdLike(name, s)) {
                    String id = idFor(name, variant);
                    if (id != null) {
                        return id;
                    }
                }
                return sample(name, s);
            }
        }
    }

    private static String sample(String name, Map<String, Object> s) {
        if (s.get("enum") instanceof List<?> e && !e.isEmpty()) {
            return String.valueOf(e.get(0));
        }
        String f = String.valueOf(s.get("format"));
        return switch (f) {
            case "uuid" -> UUID.randomUUID().toString();
            case "date" -> "2000-01-01";
            case "date-time" -> "2026-01-01T10:00:00Z";
            case "email" -> "fuzz@example.test";
            case "time", "time-local" -> "09:00:00";
            default -> {
                if ("integer".equals(s.get("type")) || "number".equals(s.get("type"))) {
                    yield "1";
                }
                Object min = s.get("minLength");
                yield "x".repeat(min instanceof Number n ? Math.max(1, n.intValue()) : 1);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Object node) {
        while (node instanceof Map<?, ?> m && m.get("$ref") instanceof String ref && ref.startsWith("#/")) {
            Object cur = spec;
            for (String part : ref.substring(2).split("/")) {
                cur = ((Map<String, Object>) cur).get(part);
            }
            node = cur;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k, x) -> j.add(quote(String.valueOf(k)) + ":" + toJson(x)));
            return j.toString();
        }
        if (v instanceof List<?> l) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            l.forEach(x -> j.add(toJson(x)));
            return j.toString();
        }
        return v instanceof Number || v instanceof Boolean ? String.valueOf(v) : quote(String.valueOf(v));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- database side (superuser connection: RLS is bypassed on purpose) ----

    private static List<String> tenantTables(Connection c) throws Exception {
        List<String> t = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t USING (table_schema, table_name) "
                        + "WHERE c.table_schema='public' AND t.table_type='BASE TABLE' AND c.column_name='tenant_id' ORDER BY 1")) {
            while (rs.next()) {
                t.add(rs.getString(1));
            }
        }
        return t;
    }

    /** B's ids by table: the id column of every tenant table, plus B's tenant id itself. */
    public static Map<String, List<String>> harvest(DataSource su, UUID tenantB) throws Exception {
        Map<String, List<String>> m = new TreeMap<>();
        try (Connection c = su.getConnection(); Statement st = c.createStatement()) {
            for (String t : tenantTables(c)) {
                List<String> ids = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT id::text FROM \"" + t + "\" WHERE tenant_id='" + tenantB + "' ORDER BY 1 LIMIT 20")) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                } catch (java.sql.SQLException noIdColumn) {
                    // table without an id column (autocommit: nothing to roll back)
                }
                if (!ids.isEmpty()) {
                    m.put(t, ids);
                }
            }
        }
        m.put("tenant", List.of(tenantB.toString()));
        return m;
    }

    public static Map<String, Long> rowCounts(DataSource su, UUID tenant) throws Exception {
        Map<String, Long> m = new TreeMap<>();
        try (Connection c = su.getConnection(); Statement st = c.createStatement()) {
            for (String t : tenantTables(c)) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + t + "\" WHERE tenant_id='" + tenant + "'")) {
                    rs.next();
                    m.put(t, rs.getLong(1));
                }
            }
        }
        return m;
    }

    /** Rows of tenant A holding any uuid column value that is one of B's ids (a write that stored a foreign reference). */
    public static List<Finding> foreignReferences(DataSource su, UUID tenantA, Collection<String> bIds) throws Exception {
        List<Finding> out = new ArrayList<>();
        if (bIds.isEmpty()) {
            return out;
        }
        String list = "'" + String.join("','", bIds) + "'";
        try (Connection c = su.getConnection(); Statement st = c.createStatement()) {
            for (String t : tenantTables(c)) {
                List<String> cols = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                        + t + "' AND data_type='uuid' AND column_name NOT IN ('tenant_id')")) {
                    while (rs.next()) {
                        cols.add(rs.getString(1));
                    }
                }
                for (String col : cols) {
                    try (ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + t + "\" WHERE tenant_id='" + tenantA + "' AND \"" + col + "\"::text IN (" + list + ")")) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            out.add(new Finding("A_ROW_REFERENCES_B_ID", "DB", t + "." + col, 0, "rows=" + rs.getLong(1), ""));
                        }
                    }
                }
            }
        }
        return out;
    }

    public Set<String> bIds() {
        return bIds;
    }
}
