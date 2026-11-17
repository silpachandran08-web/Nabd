package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CLN-024: validity and refill rules by drug class (rx_validity_rules, V3320). The rule values are the Clinical Safety Officer's
 * content, versioned with the CLN-004 safety dictionary; only the matching is code. A line matches the rules of its drug master
 * classes and schedule flags ("schedule:H1"), the dictionary's allergy classes found in its text, "controlled" when the text is on
 * the dictionary's controlled list, else the "*" rule. Several matches: the strictest value of each (shortest days, fewest refills,
 * fixed when any is fixed). The prescriber may shorten the default and lengthen it up to the class maximum; a fixed (controlled)
 * class applies its own values whatever the input.
 */
@Component
class RxValidityRules {

    static final String ANY = "*";
    static final String CONTROLLED = "controlled";
    static final String VALIDITY_ABOVE_CLASS_MAX = "VALIDITY_ABOVE_CLASS_MAX";
    static final String REFILLS_NOT_ALLOWED = "REFILLS_NOT_ALLOWED";

    record Rule(String classId, int defaultDays, int maxDays, int maxRefills, boolean fixed) {
    }

    /** version: the dictionary version that carries the rules, null when the region has none. */
    record RuleSet(Integer version, List<Rule> rules) {
    }

    /** What a line gets. days null = no rule applies (and no validity was asked for). violation: null or one of the 422 types. */
    record Applied(Integer days, int refills, Rule rule, String violation) {
    }

    private final JdbcTemplate jdbc;
    private final DrugMasterRepository masters;

    RxValidityRules(JdbcTemplate jdbc, DrugMasterRepository masters) {
        this.jdbc = jdbc;
        this.masters = masters;
    }

    /** The rules of the latest version at or below the active dictionary version that carries rules. */
    @Transactional(propagation = Propagation.MANDATORY)
    RuleSet effective(String region, int dictionaryVersion) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, class_id, default_days, max_days, max_refills, fixed " +
                "FROM rx_validity_rules WHERE region = ? AND version = (SELECT max(version) FROM rx_validity_rules WHERE region = ? AND version <= ?) " +
                "ORDER BY class_id", region, region, dictionaryVersion);
        return new RuleSet(rows.isEmpty() ? null : (Integer) rows.get(0).get("version"), rows.stream()
                .map(r -> new Rule((String) r.get("class_id"), (Integer) r.get("default_days"), (Integer) r.get("max_days"),
                        (Integer) r.get("max_refills"), (Boolean) r.get("fixed")))
                .toList());
    }

    /** The rule keys a line matches (codes: the coded line's drug master ingredient and class ids, null when uncoded). */
    @Transactional(propagation = Propagation.MANDATORY)
    Set<String> keys(SafetyDictionary dict, String drugName, String brandName, String drugId, DrugCoding.Codes codes) {
        Set<String> keys = new HashSet<>();
        if (codes != null) {
            keys.addAll(codes.classIds());
        }
        if (drugId != null) {
            masters.find(dict.region(), drugId).ifPresent(d -> d.schedule().forEach(flag -> keys.add("schedule:" + flag)));
        }
        for (String text : new String[]{drugName, brandName}) {
            if (text != null && !text.isBlank()) {
                keys.addAll(AllergyDrugClasses.classesOf(dict, text));
                if (RxSafetyChecks.controlledSubstanceWarning(dict, text) != null) {
                    keys.add(CONTROLLED);
                }
            }
        }
        return keys;
    }

    /** The strictest of the class rules the keys match, else the "*" rule, else null. classId lists the matched rules. */
    static Rule ruleFor(RuleSet set, Set<String> keys) {
        List<Rule> hit = set.rules().stream().filter(r -> keys.contains(r.classId())).toList();
        if (hit.isEmpty()) {
            hit = set.rules().stream().filter(r -> ANY.equals(r.classId())).toList();
        }
        if (hit.isEmpty()) {
            return null;
        }
        return new Rule(hit.stream().map(Rule::classId).sorted().collect(Collectors.joining(",")),
                hit.stream().mapToInt(Rule::defaultDays).min().orElseThrow(), hit.stream().mapToInt(Rule::maxDays).min().orElseThrow(),
                hit.stream().mapToInt(Rule::maxRefills).min().orElseThrow(), hit.stream().anyMatch(Rule::fixed));
    }

    /** The values a line gets from its rule and the prescriber's input (null = the default). No rule: no refills, days as asked. */
    static Applied apply(Rule rule, Integer requestedDays, Integer requestedRefills) {
        int refills = requestedRefills == null ? 0 : requestedRefills;
        if (rule == null) {
            return new Applied(requestedDays, refills, null, refills > 0 ? REFILLS_NOT_ALLOWED : null);
        }
        if (rule.fixed()) {
            return new Applied(rule.defaultDays(), 0, rule, null);
        }
        int days = requestedDays == null ? rule.defaultDays() : requestedDays;
        String violation = days > rule.maxDays() ? VALIDITY_ABOVE_CLASS_MAX : refills > rule.maxRefills() ? REFILLS_NOT_ALLOWED : null;
        return new Applied(days, refills, rule, violation);
    }

    /** 422 for a line whose input breaks its rule; nothing is saved or signed. */
    static ApiException refusal(String drugName, Applied a) {
        if (VALIDITY_ABOVE_CLASS_MAX.equals(a.violation())) {
            return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, VALIDITY_ABOVE_CLASS_MAX, "Validity above the class maximum",
                    drugName + " can be valid for at most " + a.rule().maxDays() + " days.", Map.of("maxDays", a.rule().maxDays()));
        }
        int max = a.rule() == null ? 0 : a.rule().maxRefills();
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, REFILLS_NOT_ALLOWED, "Refills not allowed",
                max == 0 ? drugName + " cannot be refilled." : drugName + " allows at most " + max + " refills.", Map.of("maxRefills", max));
    }
}
