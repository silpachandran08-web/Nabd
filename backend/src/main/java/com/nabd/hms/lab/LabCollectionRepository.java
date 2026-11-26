package com.nabd.hms.lab;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-045: sample collection repository. */
@Repository
class LabCollectionRepository {

    record SampleRow(UUID id, UUID orderId, String accession, String container, String sampleType,
                     UUID collectedBy, Instant collectedAt, Integer fastingHours, boolean nonFasting,
                     String status, Instant updatedAt) {
    }

    private static final String SAMPLE_COLS = "id, order_id, accession, container, sample_type, collected_by, "
            + "collected_at, fasting_hours, non_fasting, status, updated_at";
    private static final RowMapper<SampleRow> SAMPLE = (rs, i) -> new SampleRow(
            rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getString("accession"),
            rs.getString("container"),
            rs.getString("sample_type"),
            rs.getObject("collected_by", UUID.class),
            rs.getTimestamp("collected_at").toInstant(),
            rs.getObject("fasting_hours", Integer.class),
            rs.getBoolean("non_fasting"),
            rs.getString("status"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    LabCollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a sample and return its id. All fields are required except sample_type, received_by, received_at,
     * rejection_reason, and location.
     */
    UUID insertSample(UUID tenantId, UUID orderId, String accession, String container, String sampleType,
                      UUID collectedBy, Instant collectedAt, Integer fastingHours, boolean nonFasting) {
        return jdbc.queryForObject(
                "INSERT INTO lab_samples (tenant_id, order_id, accession, container, sample_type, collected_by, "
                        + "collected_at, fasting_hours, non_fasting, status) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, orderId, accession, container, sampleType, collectedBy,
                collectedAt, fastingHours, nonFasting, "collected");
    }

    /** Link a sample to an order item. */
    void linkSampleItem(UUID tenantId, UUID sampleId, UUID orderItemId) {
        jdbc.update("INSERT INTO lab_sample_items (tenant_id, sample_id, order_item_id) VALUES (?,?,?)",
                tenantId, sampleId, orderItemId);
    }

    /** Find a sample by accession. */
    Optional<SampleRow> findByAccession(UUID tenantId, String accession) {
        return jdbc.query("SELECT " + SAMPLE_COLS + " FROM lab_samples WHERE tenant_id = ? AND accession = ?",
                        SAMPLE, tenantId, accession)
                .stream().findFirst();
    }

    /** Find all samples for an order. */
    List<SampleRow> findByOrder(UUID tenantId, UUID orderId) {
        return jdbc.query("SELECT " + SAMPLE_COLS + " FROM lab_samples WHERE tenant_id = ? AND order_id = ? "
                        + "ORDER BY collected_at",
                SAMPLE, tenantId, orderId);
    }
}
