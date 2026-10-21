package com.nabd.hms.organization.region;

import com.nabd.hms.organization.api.RegionRules.Region;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

@Repository
class RegionRepository {

    private final JdbcTemplate jdbc;

    RegionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Region> findAll() {
        return jdbc.query("SELECT code, name, currency, minor_units, tax_regime, standard_tax_rate, regulator, default_timezone, "
                        + "phone_country_code, phone_nsn_length, cell, active FROM regions ORDER BY code",
                (rs, i) -> new Region(rs.getString("code").trim(), rs.getString("name"), rs.getString("currency").trim(),
                        rs.getInt("minor_units"), rs.getString("tax_regime"), rs.getBigDecimal("standard_tax_rate"),
                        rs.getString("regulator"), rs.getString("default_timezone"), rs.getString("phone_country_code"),
                        Arrays.asList((Integer[]) rs.getArray("phone_nsn_length").getArray()), rs.getString("cell"),
                        rs.getBoolean("active")));
    }
}
