package com.nabd.hms.clinical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** CLN-004: the v0 dictionaries (the pre-CLN-004 code lists, unchanged) for pure unit tests, built through the same
 * bundle -> entries -> snapshot path the database loader uses. SafetyDictionaryApiTest proves V1210 seeded the same. */
final class SafetyDictionaryFixtures {

    private SafetyDictionaryFixtures() {
    }

    static final SafetyDictionary IN = load("IN");
    static final SafetyDictionary SA = load("SA");

    /** Tenant region value (IN / KSA) -> the dictionary the service would pick. */
    static SafetyDictionary forTenantRegion(String tenantRegion) {
        return "SA".equals(SafetyDictionary.regionOfTenant(tenantRegion)) ? SA : IN;
    }

    static SafetyDictionaryBundle bundle(String region) {
        try (InputStream in = SafetyDictionaryFixtures.class.getResourceAsStream("/safety-dictionary/" + region + "-v0.json")) {
            return new ObjectMapper().readValue(in, SafetyDictionaryBundle.class);
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + region + "-v0.json unreadable", e);
        }
    }

    static SafetyDictionary load(String region) {
        SafetyDictionaryBundle b = bundle(region);
        return SafetyDictionary.fromEntries(region, 0, null, null, SafetyDictionaryBundles.toEntries(b), SafetyDictionaryBundles.labels(b));
    }

    /**
     * Dictionary versions are global per region and shared by every test class on the container. Captures the exact
     * state now; the returned Runnable puts it back (drops versions created since, restores status / approval /
     * activation of the rest) and clears the service's cache, so class order cannot matter.
     */
    static Runnable snapshotState(javax.sql.DataSource superuser, SafetyDictionaryService service) {
        org.springframework.jdbc.core.JdbcTemplate su = new org.springframework.jdbc.core.JdbcTemplate(superuser);
        List<Map<String, Object>> rows = su.queryForList(
                "SELECT id, status, cso_approval_ref, approved_by, approved_at, activated_at FROM safety_dictionary_versions");
        return () -> {
            org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(superuser));
            tx.executeWithoutResult(s -> {
                String keep = rows.stream().map(r -> "'" + r.get("id") + "'").reduce((a, b) -> a + "," + b)
                        .map(l -> "id IN (" + l + ")").orElse("false"); // uuids we just read, not user input
                su.update("DELETE FROM safety_dictionary_entries WHERE version_id IN (SELECT id FROM safety_dictionary_versions WHERE NOT (" + keep + "))");
                su.update("DELETE FROM safety_dictionary_versions WHERE NOT (" + keep + ")");
                su.update("UPDATE safety_dictionary_versions SET status = 'retired'"); // first, so the one-active index never sees two
                for (Map<String, Object> r : rows) {
                    su.update("UPDATE safety_dictionary_versions SET status = ?, cso_approval_ref = ?, approved_by = ?, approved_at = ?, " +
                            "activated_at = ? WHERE id = ?", r.get("status"), r.get("cso_approval_ref"), r.get("approved_by"),
                            r.get("approved_at"), r.get("activated_at"), r.get("id"));
                }
            });
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
            ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(target, "active")).clear();
        };
    }
}
