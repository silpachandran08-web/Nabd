package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.PlatformAudit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CLN-032: the loader of the licensed interaction knowledge base. The feed is the Nabd-normalised JSON-lines form (the
 * licensed KB's own format gets an adapter that writes it, as for the CLN-019 drug master): line 1 is the header
 * {@code {"kb","month":"YYYY-MM","region":"IN|SA|AE|QA","kind":"interactions","duplicateIngredientSeverity"}}; every other
 * line is {@code {"type":"interaction","a","b","severity","mechanism","management","sourceRef"}} (a, b: drug master
 * ingredient ids) or {@code {"type":"duplicate-class","classId","severity","sourceRef"}} (a drug master class the KB
 * marks as non-additive). severity: contraindicated | major | moderate | minor.
 *
 * load = parse, validate and store the whole file as a new version in ONE transaction: any invalid record (unknown
 * ingredient or class, repeated pair, bad severity) refuses the file and nothing is kept. A loaded version is invisible
 * to prescribers until activate, which always needs the Clinical Safety Officer's reference; activating an earlier
 * version again is the way back. This service ships and invents no content.
 */
@Service
public class DrugInteractionImportService {

    private static final int BATCH = 1000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final DrugInteractionRepository repo;
    private final DrugMasterImportService feeds;
    private final PlatformAudit platformAudit;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    DrugInteractionImportService(DrugInteractionRepository repo, DrugMasterImportService feeds, PlatformAudit platformAudit,
                                 ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.feeds = feeds;
        this.platformAudit = platformAudit;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    public Map<String, Object> load(String feedUri, UUID operatorId, String ip) {
        Path file = feeds.resolve(feedUri);
        String checksum = DrugMasterImportService.sha256(file);
        Map<String, Object> out;
        try {
            out = tx.execute(x -> read(file, checksum, "operator:" + operatorId));
        } catch (DrugFeedAdapter.FeedException e) {
            throw DrugMasterImportService.invalid("FEED_INVALID", e.getMessage());
        }
        if (Boolean.TRUE.equals(out.remove("created"))) {
            Map<String, Object> after = new LinkedHashMap<>(out);
            after.put("checksum", checksum);
            platformAudit.record(null, operatorId, ip, "drug_interactions.version_loaded", "drug_interaction_version",
                    (UUID) out.get("importId"), after);
        }
        return out;
    }

    // Runs inside load()'s TransactionTemplate; the annotation documents that (and satisfies TenancyRulesTest), it is not what opens it.
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private Map<String, Object> read(Path file, String checksum, String by) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null) {
                throw new DrugFeedAdapter.FeedException("feed is empty");
            }
            Map<String, Object> h = map(line, 1);
            String region = str(h, "region", 1, 2, true);
            String month = str(h, "month", 1, 7, true);
            String duplicateSeverity = severity(h, "duplicateIngredientSeverity", 1);
            if (!JsonLinesDrugFeedAdapter.REGIONS.contains(region) || !MONTH.matcher(month).matches() || !"interactions".equals(h.get("kind"))) {
                throw new DrugFeedAdapter.FeedException("record 1: header needs region (IN, SA, AE, QA), kind (interactions) and month (YYYY-MM)");
            }
            DrugInteractionRepository.Started s = repo.start(region, str(h, "kb", 1, 80, true), month, duplicateSeverity, checksum, by);
            out.put("importId", s.importId());
            out.put("region", region);
            out.put("kbVersion", s.kbVersion());
            out.put("created", s.created());
            if (!s.created()) {
                out.put("status", s.status()); // the same file was loaded before: that version is the answer
                return out;
            }
            List<Map<String, Object>> batch = new ArrayList<>(BATCH);
            Set<String> seen = new HashSet<>();
            int n = 1;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isBlank()) {
                    continue;
                }
                batch.add(row(map(line, n), n, seen));
                if (batch.size() == BATCH) {
                    flush(s.importId(), batch);
                }
            }
            flush(s.importId(), batch);
            if (seen.isEmpty()) {
                // an active version with nothing in it would make every prescription look checked
                throw new DrugFeedAdapter.FeedException("feed has no interaction or duplicate-class record");
            }
            int[] counts = repo.finish(s.importId());
            out.put("status", "validated");
            out.put("pairs", counts[0]);
            out.put("duplicateClasses", counts[1]);
            return out;
        } catch (IOException e) {
            throw new DrugFeedAdapter.FeedException("feed cannot be read");
        }
    }

    /** One validated record, with "_n" (its record number) for the unknown-id message of the batch check. */
    private static Map<String, Object> row(Map<String, Object> m, int n, Set<String> seen) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = str(m, "type", n, 20, true);
        out.put("type", type);
        out.put("_n", n);
        out.put("severity", severity(m, "severity", n));
        out.put("sourceRef", str(m, "sourceRef", n, 500, false));
        String key;
        if ("interaction".equals(type)) {
            String a = id(m, "a", n);
            String b = id(m, "b", n);
            if (a.equals(b)) {
                throw new DrugFeedAdapter.FeedException("record " + n + ": an interaction needs two different ingredients");
            }
            out.put("a", a);
            out.put("b", b);
            out.put("mechanism", str(m, "mechanism", n, 2000, false));
            out.put("management", str(m, "management", n, 2000, false));
            key = "p:" + RxInteractionRules.Kb.key(a, b);
        } else if ("duplicate-class".equals(type)) {
            out.put("classId", id(m, "classId", n));
            key = "c:" + out.get("classId");
        } else {
            throw new DrugFeedAdapter.FeedException("record " + n + ": type must be interaction or duplicate-class");
        }
        if (!seen.add(key)) {
            throw new DrugFeedAdapter.FeedException("record " + n + ": the same pair or class appears twice");
        }
        return out;
    }

    // Runs inside load()'s TransactionTemplate; the annotation documents that (and satisfies TenancyRulesTest), it is not what opens it.
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private void flush(UUID importId, List<Map<String, Object>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Map<String, Integer> ingredients = new LinkedHashMap<>();
        Map<String, Integer> classes = new LinkedHashMap<>();
        for (Map<String, Object> r : batch) {
            int n = (Integer) r.get("_n");
            for (String k : List.of("a", "b")) {
                if (r.get(k) != null) {
                    ingredients.putIfAbsent((String) r.get(k), n);
                }
            }
            if (r.get("classId") != null) {
                classes.putIfAbsent((String) r.get("classId"), n);
            }
        }
        Set<String> knownIngredients = repo.knownIngredients(ingredients.keySet());
        Set<String> knownClasses = repo.knownClasses(classes.keySet());
        ingredients.forEach((id, n) -> {
            if (!knownIngredients.contains(id)) {
                throw new DrugFeedAdapter.FeedException("record " + n + ": ingredient is not in the drug master");
            }
        });
        classes.forEach((id, n) -> {
            if (!knownClasses.contains(id)) {
                throw new DrugFeedAdapter.FeedException("record " + n + ": class is not in the drug master");
            }
        });
        repo.batch(importId, mapper.writeValueAsString(batch));
        batch.clear();
    }

    /** The version switch. Idempotent: activating the active version returns it again. */
    public Map<String, Object> activate(UUID importId, String csoAckRef, UUID operatorId, String ip) {
        DrugInteractionRepository.Activated a = tx.execute(x -> repo.activate(importId, csoAckRef));
        switch (a.outcome()) {
            case "activated" -> platformAudit.record(null, operatorId, ip, "drug_interactions.version_activated", "drug_interaction_version",
                    importId, Map.of("region", a.region(), "kbVersion", a.kbVersion(), "csoAckRef", csoAckRef.trim()));
            case "already_active" -> { }
            case "cso_required" -> throw new ApiException(HttpStatus.CONFLICT, "CSO_ACK_REQUIRED", "CSO acknowledgement required",
                    "An interaction knowledge-base version is clinical content; send csoAckRef.");
            case "not_found" -> throw new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Import not found",
                    "No such interaction knowledge-base version.");
            default -> throw new ApiException(HttpStatus.CONFLICT, "IMPORT_INVALID", "Version cannot be activated",
                    "Only a fully loaded version can be activated (" + a.outcome() + ").");
        }
        return Map.of("region", a.region(), "kbVersion", a.kbVersion(), "status", "active");
    }

    // ── helpers (messages name the record number and the rule, never the record's values) ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String line, int n) {
        try {
            return mapper.readValue(line, Map.class);
        } catch (RuntimeException e) {
            throw new DrugFeedAdapter.FeedException("record " + n + ": not a JSON object");
        }
    }

    private static String id(Map<String, Object> m, String key, int n) {
        String v = str(m, key, n, 64, true);
        if (!ID.matcher(v).matches()) {
            throw new DrugFeedAdapter.FeedException("record " + n + ": " + key + " has characters outside A-Z a-z 0-9 . _ -");
        }
        return v;
    }

    private static String severity(Map<String, Object> m, String key, int n) {
        String v = str(m, key, n, 20, true);
        if (!RxInteractionRules.SEVERITIES.contains(v)) {
            throw new DrugFeedAdapter.FeedException("record " + n + ": " + key + " must be contraindicated, major, moderate or minor");
        }
        return v;
    }

    private static String str(Map<String, Object> m, String key, int n, int max, boolean required) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                throw new DrugFeedAdapter.FeedException("record " + n + ": " + key + " is required");
            }
            return null;
        }
        if (!(v instanceof String s) || s.trim().length() > max) {
            throw new DrugFeedAdapter.FeedException("record " + n + ": " + key + " must be text of at most " + max + " characters");
        }
        return s.trim();
    }
}
