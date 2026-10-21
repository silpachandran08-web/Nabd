package com.nabd.hms.organization.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * OPS-061: region reference data (regions table) for every module that has a regional rule: currency, tax regime and
 * headline rate, regulator, default time zone, phone country, cell. Callers pass the tenant's region_code and never
 * switch on a region code themselves. FIN-006 reads currency and tax identifiers through here.
 */
public interface RegionRules {

    /** @throws IllegalArgumentException for a code that is not in the regions table */
    Region region(String code);

    /** Every region, active or not, ordered by code. */
    List<Region> all();

    /**
     * @param standardTaxRate the headline rate in percent (AE 5, SA 15, QA 0), or null where there is no single rate (India GST slabs)
     * @param phoneNsnLength  advisory national-number lengths; validation itself is libphonenumber
     */
    record Region(String code, String name, String currency, int minorUnits, String taxRegime, BigDecimal standardTaxRate,
                  String regulator, String defaultTimezone, String phoneCountryCode, List<Integer> phoneNsnLength,
                  String cell, boolean active) {
    }
}
