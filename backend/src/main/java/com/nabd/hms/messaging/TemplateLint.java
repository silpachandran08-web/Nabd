package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COM-014: PHI lint run before a template is submitted. Nothing medical may sit on a lock screen (PS-06): the body is
 * checked against the deny words in template_lint_rules (any language) and the active safety dictionary's drug terms
 * (the drug master), and every {{n}} must be a typed slot from the allow-list. Rules are read per call (small reference
 * tables), so a rule change by migration applies at once.
 */
@Component
public class TemplateLint {

    @Schema(name = "TemplateLintSlot")
    public record Slot(int n, String type) {
    }

    @Schema(name = "TemplateLintFinding")
    public record Finding(String phrase, String rule) {
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\d+)}}");
    private static final Pattern LATIN = Pattern.compile("[\\p{IsLatin}\\p{N} \\-]+");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u00AD\\u064B-\\u065F\\u0670\\u0640]");
    private static final String DRUGS = """
            SELECT DISTINCT e.value FROM safety_dictionary_entries e
              JOIN safety_dictionary_versions v ON v.id = e.version_id
             WHERE v.status = 'active' AND e.match_kind = 'term' AND length(e.value) >= 4
               AND e.kind IN ('allergy_class', 'pregnancy_risk', 'controlled')""";

    private record Hit(int start, int end, String rule) {
    }

    private final JdbcTemplate jdbc;

    TemplateLint(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Scripts folded so look-alikes do not slip through: NFKC, zero-width and soft hyphens, Arabic marks, one space. */
    static String normalise(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC);
        return INVISIBLE.matcher(n).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    /** @param slots null when the caller declares none: only the wording is checked then (shortcut: custom templates
     *              created before typed slots existed; the editor sends types and the catalogue always has them) */
    public List<Finding> lint(String body, List<Slot> slots) {
        String text = normalise(body);
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT pattern FROM template_lint_rules WHERE kind = 'deny_word'")) {
            collect(text, (String) r.get("pattern"), "deny_word", hits);
        }
        for (String drug : jdbc.queryForList(DRUGS, String.class)) {
            collect(text, drug, "drug_master", hits);
        }
        List<Finding> findings = new ArrayList<>(merge(text, hits));
        if (slots != null) {
            findings.addAll(slotFindings(body, slots));
        }
        return findings;
    }

    /** @throws ApiException 422 TEMPLATE_PHI naming the offending phrase(s); nothing has been sent to Meta */
    public void enforce(String body, List<Slot> slots) {
        List<Finding> findings = lint(body, slots);
        if (findings.isEmpty()) {
            return;
        }
        String phrases = String.join(", ", findings.stream().map(f -> "'" + f.phrase() + "'").distinct().toList());
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "template-phi", "Template not allowed on a lock screen",
                "This template can't be submitted: " + phrases + ". Messages carry no medical wording; put it behind a secure link.",
                Map.of("code", "TEMPLATE_PHI", "findings", findings));
    }

    private List<Finding> slotFindings(String body, List<Slot> slots) {
        Set<String> deny = new HashSet<>(jdbc.queryForList("SELECT pattern FROM template_lint_rules WHERE kind = 'deny_slot'", String.class));
        Set<String> allow = new HashSet<>(jdbc.queryForList("SELECT pattern FROM template_lint_rules WHERE kind = 'allow_slot'", String.class));
        List<Finding> out = new ArrayList<>();
        Set<Integer> typed = new HashSet<>();
        for (Slot s : slots) {
            String type = s == null || s.type() == null ? "" : s.type().strip().toLowerCase();
            if (s != null) {
                typed.add(s.n());
            }
            if (deny.contains(type)) {
                out.add(new Finding(type, "deny_slot"));
            } else if (!allow.contains(type)) {
                out.add(new Finding(type.isEmpty() ? "(no type)" : type, "allow_slot"));
            }
        }
        Matcher m = VARIABLE.matcher(body == null ? "" : body);
        while (m.find()) {
            if (!typed.contains(Integer.parseInt(m.group(1)))) {
                out.add(new Finding(m.group(), "slot_untyped"));
            }
        }
        return out;
    }

    private static void collect(String text, String rawPattern, String rule, List<Hit> hits) {
        String p = normalise(rawPattern);
        if (p.isEmpty()) {
            return;
        }
        // Latin terms must be whole words; Indic and Arabic terms inflect and attach to neighbours, so they match as substrings.
        String regex = LATIN.matcher(p).matches()
                ? "(?<![\\p{L}\\p{N}])" + Pattern.quote(p) + "(?![\\p{L}\\p{N}])" : Pattern.quote(p);
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        while (m.find()) {
            hits.add(new Hit(m.start(), m.end(), rule));
        }
    }

    /** Overlapping or space-separated hits become one phrase, so "HbA1c result" is reported whole. */
    private static List<Finding> merge(String text, List<Hit> hits) {
        List<Finding> out = new ArrayList<>();
        hits.sort(Comparator.comparingInt(Hit::start).thenComparingInt(h -> -h.end()));
        int i = 0;
        while (i < hits.size()) {
            int start = hits.get(i).start();
            int end = hits.get(i).end();
            String rule = hits.get(i).rule();
            i++;
            while (i < hits.size() && hits.get(i).start() <= end + (end < text.length() && text.charAt(end) == ' ' ? 1 : 0)) {
                end = Math.max(end, hits.get(i).end());
                i++;
            }
            out.add(new Finding(text.substring(start, end), rule));
        }
        return out;
    }
}
