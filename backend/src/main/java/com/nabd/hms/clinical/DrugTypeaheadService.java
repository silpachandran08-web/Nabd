package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.DrugSuggestionResponse;
import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** CLN-020: the drug typeahead (server fallback) and the per-doctor client index. Reference data and counts only, no PHI. */
@Service
class DrugTypeaheadService {

    static final int INDEX_SIZE = 3000;
    private static final int MAX_TERM = 60;

    /** Gzipped JSON body and its ETag (a hash of the uncompressed body: it changes with the drug master version, the
     * display rule and this doctor's / clinic's usage ranking, nothing else). */
    record Index(byte[] body, String etag) {
    }

    private final PrescriptionRepository prescriptions;
    private final DrugMasterRepository masters;
    private final DrugCoding coding;
    private final TenantContext tenantContext;
    private final ObjectMapper json;

    DrugTypeaheadService(PrescriptionRepository prescriptions, DrugMasterRepository masters, DrugCoding coding,
                         TenantContext tenantContext, ObjectMapper json) {
        this.prescriptions = prescriptions;
        this.masters = masters;
        this.coding = coding;
        this.tenantContext = tenantContext;
        this.json = json;
    }

    @Transactional
    List<DrugSuggestionResponse> search(UUID staffId, String q, int limit) {
        UUID tenantId = tenantContext.current();
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (term.length() < 2) {
            return List.of();
        }
        term = term.length() > MAX_TERM ? term.substring(0, MAX_TERM) : term;
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        // Trigram only helps from 3 characters; two letters use the prefix path (btree + GIN prefix), still index-only.
        String brandLike = term.length() >= 3 ? "%" + escaped + "%" : escaped + "%";
        boolean first = coding.genericFirst();
        return prescriptions.searchDrugs(tenantId, staffId, coding.region(), tsOf(term),
                        brandLike, "%" + escaped + "%", escaped + "%", Math.max(1, Math.min(limit, 20))).stream()
                .map(r -> {
                    if (r.drugId() == null) {
                        return new DrugSuggestionResponse(r.name().trim(), r.source(), r.code(), r.inStock(), r.prescriptionOnly(),
                                null, null, null, null, null, null, null);
                    }
                    String generic = DrugDisplay.generic(r.generic());
                    String display = DrugDisplay.display(generic, r.brand(), r.strength(), r.form(), first);
                    return new DrugSuggestionResponse(display, r.source(), null, r.inStock(), r.prescriptionOnly(), r.drugId(), display,
                            generic, r.brand(), r.strength(), r.form(), r.schedule());
                }).toList();
    }

    /** A term with punctuation (LIKE wildcards among them) is matched literally by the LIKE arms only, never as a word prefix. */
    private static String tsOf(String term) {
        String ts = term.matches("[\\p{L}\\p{N} ]+") ? DrugDisplay.tsQuery(term) : "";
        return ts.isEmpty() ? "zzzznone" : ts;
    }

    @Transactional
    Index clientIndex(UUID staffId) {
        UUID tenantId = tenantContext.current();
        String region = coding.region();
        boolean first = coding.genericFirst();
        List<Object> items = new ArrayList<>();
        for (DrugMasterRepository.IndexRow r : masters.indexRows(tenantId, staffId, region, INDEX_SIZE)) {
            String generic = DrugDisplay.generic(r.generic());
            items.add(new Object[]{r.id(), DrugDisplay.display(generic, r.brand(), r.strength(), r.form(), first), generic, r.brand(),
                    r.strength(), r.form(), r.schedule()});
        }
        try {
            // items: [drugId, display, generic, brand, strength, form, schedule[]]; the array order is the ranking.
            int master = masters.masterVersion(region);
            // rev (= the ETag value) is also inside the body: the browser client cannot read response headers.
            // Explicit insertion order: the hash must not depend on the JVM (Map.of order is randomised per run).
            java.util.Map<String, Object> hashed = new java.util.LinkedHashMap<>();
            hashed.put("master", master);
            hashed.put("items", items);
            String rev = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(hashed)), 0, 16);
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("v", 1);
            body.put("master", master);
            body.put("rev", rev);
            body.put("items", items);
            byte[] raw = json.writeValueAsBytes(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(raw);
            }
            return new Index(out.toByteArray(), "\"" + rev + "\"");
        } catch (Exception e) {
            throw new IllegalStateException("drug client index could not be built", e);
        }
    }

}
