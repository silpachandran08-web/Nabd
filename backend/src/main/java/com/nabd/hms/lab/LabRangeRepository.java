package com.nabd.hms.lab;

import com.nabd.hms.lab.LabRangeDtos.RangeBand;
import com.nabd.hms.lab.LabRangeDtos.RangeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PHL-042: lab_reference_ranges (V3640). Insert-only: a version's rows are never updated or deleted. */
@Repository
class LabRangeRepository {

    private final JdbcTemplate jdbc;

    LabRangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 0 when the test has no ranges. */
    int latestVersion(UUID tenantId, UUID testId) {
        return jdbc.queryForObject("SELECT coalesce(max(version), 0) FROM lab_reference_ranges WHERE tenant_id = ? AND test_id = ?",
                Integer.class, tenantId, testId);
    }

    /** The bands of one version, youngest first; no rows gives an empty set with that version number. */
    RangeSet set(UUID tenantId, UUID testId, int version) {
        List<RangeBand> bands = new ArrayList<>();
        Instant[] from = new Instant[1];
        jdbc.query("SELECT sex, age_min_days, age_max_days, low, high, critical_low, critical_high, text_range, effective_from "
                + "FROM lab_reference_ranges WHERE tenant_id = ? AND test_id = ? AND version = ? ORDER BY age_min_days, sex, id", rs -> {
            from[0] = rs.getTimestamp("effective_from").toInstant();
            bands.add(new RangeBand(rs.getString("sex"), rs.getInt("age_min_days"), rs.getObject("age_max_days", Integer.class),
                    rs.getBigDecimal("low"), rs.getBigDecimal("high"), rs.getBigDecimal("critical_low"), rs.getBigDecimal("critical_high"),
                    rs.getString("text_range")));
        }, tenantId, testId, version);
        return new RangeSet(version, from[0], bands);
    }

    void insert(UUID tenantId, UUID testId, int version, List<RangeBand> bands) {
        jdbc.batchUpdate("INSERT INTO lab_reference_ranges (tenant_id, test_id, version, sex, age_min_days, age_max_days, low, high, "
                + "critical_low, critical_high, text_range) VALUES (?,?,?,?,?,?,?,?,?,?,?)", bands, bands.size(), (ps, b) -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, testId);
            ps.setInt(3, version);
            ps.setString(4, b.sex());
            ps.setInt(5, b.ageMinDays());
            ps.setObject(6, b.ageMaxDays());
            ps.setBigDecimal(7, b.low());
            ps.setBigDecimal(8, b.high());
            ps.setBigDecimal(9, b.criticalLow());
            ps.setBigDecimal(10, b.criticalHigh());
            ps.setString(11, b.text() == null || b.text().isBlank() ? null : b.text().trim());
        });
    }
}
