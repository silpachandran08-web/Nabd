package com.nabd.hms.clinical;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * CLN-019: the Nabd-normalised feed, one JSON object per line. Line 1 is the header
 * {@code {"kb","month":"YYYY-MM","region":"IN|SA|AE|QA","kind":"full|delta","classes":[{"id","name"}]}}; every other line is a
 * product {@code {"kbId","brand","strength","form","route","schedule":[..],"pregnancyCategory","lactationNote","status":"active|retired",
 * "ingredients":[{"id","name","inn","synonyms":[..],"classIds":[..],"amount","unit"}]}}. The licensed KB's own format (unknown
 * until CLN-018 delivers it) gets its own adapter producing the same records.
 */
@Component
class JsonLinesDrugFeedAdapter implements DrugFeedAdapter {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9 _/-]{1,40}");
    static final Set<String> REGIONS = Set.of("IN", "SA", "AE", "QA");

    private final ObjectMapper mapper;

    JsonLinesDrugFeedAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Header header(Path file) {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null) {
                throw new FeedException("feed is empty");
            }
            return parseHeader(map(line, 1));
        } catch (IOException e) {
            throw new FeedException("feed cannot be read");
        }
    }

    @Override
    public void products(Path file, Consumer<Map<String, Object>> sink) {
        Header h = header(file);
        Set<String> classIds = new HashSet<>();
        h.classes().forEach(c -> classIds.add(c.id()));
        Set<String> seen = new HashSet<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            r.readLine();
            String line;
            int n = 1;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> p = product(map(line, n), n, classIds);
                if (!seen.add((String) p.get("kbId"))) {
                    throw new FeedException("record " + n + ": duplicate kbId");
                }
                sink.accept(p);
            }
        } catch (IOException e) {
            throw new FeedException("feed cannot be read");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String line, int n) {
        try {
            return mapper.readValue(line, Map.class);
        } catch (RuntimeException e) {
            throw new FeedException("record " + n + ": not a JSON object");
        }
    }

    private Header parseHeader(Map<String, Object> m) {
        String region = str(m, "region", 1, 2, true);
        String kind = str(m, "kind", 1, 5, true);
        String month = str(m, "month", 1, 7, true);
        if (!REGIONS.contains(region) || !(kind.equals("full") || kind.equals("delta")) || !MONTH.matcher(month).matches()) {
            throw new FeedException("record 1: header needs region (IN, SA, AE, QA), kind (full, delta) and month (YYYY-MM)");
        }
        List<DrugClass> classes = new ArrayList<>();
        for (Object o : list(m.get("classes"), 1, "classes")) {
            Map<?, ?> c = o instanceof Map<?, ?> x ? x : Map.of();
            String id = c.get("id") instanceof String s ? s : "";
            String name = c.get("name") instanceof String s ? s.trim() : "";
            if (!ID.matcher(id).matches() || name.isEmpty() || name.length() > 120) {
                throw new FeedException("record 1: every class needs an id and a name");
            }
            classes.add(new DrugClass(id, name));
        }
        return new Header(str(m, "kb", 1, 80, true), month, region, kind, classes);
    }

    private Map<String, Object> product(Map<String, Object> m, int n, Set<String> classIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        String kbId = str(m, "kbId", n, 64, true);
        if (!ID.matcher(kbId).matches()) {
            throw new FeedException("record " + n + ": kbId has characters outside A-Z a-z 0-9 . _ -");
        }
        out.put("kbId", kbId);
        out.put("brand", str(m, "brand", n, 200, true));
        out.put("strength", str(m, "strength", n, 100, false));
        out.put("form", str(m, "form", n, 100, false));
        out.put("route", str(m, "route", n, 100, false));
        out.put("pregnancyCategory", str(m, "pregnancyCategory", n, 20, false));
        out.put("lactationNote", str(m, "lactationNote", n, 500, false));
        String status = m.get("status") == null ? "active" : str(m, "status", n, 10, true);
        if (!status.equals("active") && !status.equals("retired")) {
            throw new FeedException("record " + n + ": status must be active or retired");
        }
        out.put("status", status);
        TreeSet<String> schedule = new TreeSet<>();
        for (Object s : list(m.get("schedule"), n, "schedule")) {
            if (!(s instanceof String t) || !CODE.matcher(t).matches()) {
                throw new FeedException("record " + n + ": invalid schedule code");
            }
            schedule.add(t.trim());
        }
        out.put("schedule", new ArrayList<>(schedule));
        Map<String, Map<String, Object>> byId = new java.util.TreeMap<>();
        for (Object o : list(m.get("ingredients"), n, "ingredients")) {
            Map<String, Object> g = o instanceof Map<?, ?> x ? castMap(x) : Map.of();
            String id = str(g, "id", n, 64, true);
            if (!ID.matcher(id).matches() || byId.containsKey(id)) {
                throw new FeedException("record " + n + ": ingredient id invalid or repeated");
            }
            Map<String, Object> ing = new LinkedHashMap<>();
            ing.put("id", id);
            ing.put("name", str(g, "name", n, 200, true));
            ing.put("inn", str(g, "inn", n, 200, false));
            ing.put("unit", str(g, "unit", n, 20, false));
            ing.put("amount", amount(g.get("amount"), n));
            List<String> syn = new ArrayList<>();
            for (Object s : list(g.get("synonyms"), n, "synonyms")) {
                if (!(s instanceof String t) || t.isBlank() || t.length() > 200) {
                    throw new FeedException("record " + n + ": invalid ingredient synonym");
                }
                syn.add(t.trim());
            }
            ing.put("synonyms", syn);
            List<String> cls = new ArrayList<>();
            for (Object s : list(g.get("classIds"), n, "classIds")) {
                if (!(s instanceof String t) || !classIds.contains(t)) {
                    throw new FeedException("record " + n + ": ingredient class is not declared in the header");
                }
                cls.add(t);
            }
            ing.put("classIds", cls);
            byId.put(id, ing);
        }
        if (status.equals("active") && byId.isEmpty()) {
            throw new FeedException("record " + n + ": an active product needs at least one ingredient");
        }
        out.put("ingredients", new ArrayList<>(byId.values()));
        return out;
    }

    private static Object amount(Object o, int n) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number x && x.doubleValue() >= 0 && x.doubleValue() < 1e9) {
            return x;
        }
        throw new FeedException("record " + n + ": ingredient amount must be a number >= 0");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static List<?> list(Object o, int n, String field) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l && l.size() <= 200) {
            return l;
        }
        throw new FeedException("record " + n + ": " + field + " must be a list of at most 200");
    }

    private static String str(Map<String, Object> m, String key, int n, int max, boolean required) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                throw new FeedException("record " + n + ": " + key + " is required");
            }
            return null;
        }
        if (!(v instanceof String s) || s.trim().length() > max) {
            throw new FeedException("record " + n + ": " + key + " must be text of at most " + max + " characters");
        }
        return s.trim();
    }
}
