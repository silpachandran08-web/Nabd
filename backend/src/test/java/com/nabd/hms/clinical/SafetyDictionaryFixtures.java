package com.nabd.hms.clinical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;

import java.io.InputStream;

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
}
