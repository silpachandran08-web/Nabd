package com.nabd.hms.insurance.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FIN-036: SQL of the insurance module's own tables. insurance_payers and insurance_plans hold global rows (tenant_id NULL,
 * readable by every clinic through RLS) next to the clinic's own; every write here is a row of the bound tenant.
 */
@Repository
class InsuranceRepository {

    record PayerRow(UUID id, UUID tenantId, String name, String kind, String jurisdiction, Map<String, String> identifiers, boolean active) {
    }

    record PlanRow(UUID id, UUID tenantId, UUID payerId, String name, BigDecimal copayPercent, boolean active) {
    }

    record PolicyRow(UUID id, UUID patientId, UUID payerId, String payerName, UUID planId, String planName, BigDecimal copayPercent,
                     byte[] memberIdEnc, byte[] policyNumberEnc, LocalDate validFrom, LocalDate validTo, String relationship,
                     List<UUID> cardDocumentIds, String status, Instant createdAt) {
    }

    record CoverageRow(UUID visitId, String mode, UUID policyId, UUID payerId, String payerName, String eligibilityRef, BigDecimal copayPercent) {
    }

    private static final String POLICY_SELECT = "SELECT pp.id, pp.patient_id, pp.payer_id, py.name AS payer_name, pp.plan_id, pl.name AS plan_name, " +
            "pl.copay_percent, pp.member_id_enc, pp.policy_number_enc, pp.valid_from, pp.valid_to, pp.relationship, pp.card_document_ids, pp.status, " +
            "pp.created_at FROM patient_policies pp JOIN insurance_payers py ON py.id = pp.payer_id LEFT JOIN insurance_plans pl ON pl.id = pp.plan_id ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    InsuranceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private PayerRow payer(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PayerRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"), rs.getString("kind"),
                rs.getString("jurisdiction"), json.readValue(rs.getString("identifiers"), new TypeReference<Map<String, String>>() { }), rs.getBoolean("active"));
    }

    private static final RowMapper<PlanRow> PLAN = (rs, i) -> new PlanRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("payer_id", UUID.class), rs.getString("name"), rs.getBigDecimal("copay_percent"), rs.getBoolean("active"));

    private static final RowMapper<PolicyRow> POLICY = (rs, i) -> new PolicyRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getObject("payer_id", UUID.class), rs.getString("payer_name"), rs.getObject("plan_id", UUID.class), rs.getString("plan_name"),
            rs.getBigDecimal("copay_percent"), rs.getBytes("member_id_enc"), rs.getBytes("policy_number_enc"),
            rs.getDate("valid_from").toLocalDate(), rs.getDate("valid_to").toLocalDate(), rs.getString("relationship"),
            List.of((UUID[]) rs.getArray("card_document_ids").getArray()), rs.getString("status"), rs.getTimestamp("created_at").toInstant());

    /** Active payers the clinic may use: the global master plus its own, by name; at most 200. */
    List<PayerRow> payers(UUID tenantId, String jurisdiction, String q) {
        StringBuilder sql = new StringBuilder("SELECT id, tenant_id, name, kind, jurisdiction, identifiers::text AS identifiers, active FROM insurance_payers " +
                "WHERE (tenant_id IS NULL OR tenant_id = ?) AND active");
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (jurisdiction != null) {
            sql.append(" AND jurisdiction = ?");
            args.add(jurisdiction);
        }
        if (q != null) {
            sql.append(" AND name ILIKE ?");
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return jdbc.query(sql.append(" ORDER BY lower(name), id LIMIT 200").toString(), this::payer, args.toArray());
    }

    Optional<PayerRow> findPayer(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, tenant_id, name, kind, jurisdiction, identifiers::text AS identifiers, active FROM insurance_payers " +
                "WHERE (tenant_id IS NULL OR tenant_id = ?) AND id = ?", this::payer, tenantId, id).stream().findFirst();
    }

    List<PlanRow> plans(UUID tenantId, List<UUID> payerIds) {
        if (payerIds.isEmpty()) return List.of();
        return jdbc.query("SELECT id, tenant_id, payer_id, name, copay_percent, active FROM insurance_plans " +
                        "WHERE (tenant_id IS NULL OR tenant_id = ?) AND active AND payer_id = ANY (?) ORDER BY lower(name), id",
                PLAN, tenantId, payerIds.toArray(UUID[]::new));
    }

    Optional<PlanRow> findPlan(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, tenant_id, payer_id, name, copay_percent, active FROM insurance_plans " +
                "WHERE (tenant_id IS NULL OR tenant_id = ?) AND id = ?", PLAN, tenantId, id).stream().findFirst();
    }

    UUID insertPayer(UUID tenantId, String name, String kind, String jurisdiction, Map<String, String> identifiers) {
        return jdbc.queryForObject("INSERT INTO insurance_payers (tenant_id, name, kind, jurisdiction, identifiers) VALUES (?,?,?,?,?::jsonb) RETURNING id",
                UUID.class, tenantId, name, kind, jurisdiction, json.writeValueAsString(identifiers));
    }

    UUID insertPlan(UUID tenantId, UUID payerId, String name, BigDecimal copayPercent) {
        return jdbc.queryForObject("INSERT INTO insurance_plans (tenant_id, payer_id, name, copay_percent) VALUES (?,?,?,?) RETURNING id",
                UUID.class, tenantId, payerId, name, copayPercent);
    }

    void insertPolicy(UUID tenantId, UUID id, UUID patientId, UUID payerId, UUID planId, byte[] memberIdEnc, byte[] policyNumberEnc,
                      LocalDate validFrom, LocalDate validTo, String relationship, List<UUID> cardDocumentIds, UUID staffId) {
        jdbc.update("INSERT INTO patient_policies (id, tenant_id, patient_id, payer_id, plan_id, member_id_enc, policy_number_enc, valid_from, valid_to, " +
                        "relationship, card_document_ids, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, patientId, payerId, planId, memberIdEnc, policyNumberEnc, Date.valueOf(validFrom), Date.valueOf(validTo), relationship,
                cardDocumentIds.toArray(UUID[]::new), staffId);
    }

    /** Newest first. */
    List<PolicyRow> policies(UUID tenantId, UUID patientId) {
        return jdbc.query(POLICY_SELECT + "WHERE pp.tenant_id = ? AND pp.patient_id = ? ORDER BY pp.valid_to DESC, pp.id DESC", POLICY, tenantId, patientId);
    }

    Optional<PolicyRow> findPolicy(UUID tenantId, UUID id) {
        return jdbc.query(POLICY_SELECT + "WHERE pp.tenant_id = ? AND pp.id = ?", POLICY, tenantId, id).stream().findFirst();
    }

    Optional<CoverageRow> findCoverage(UUID tenantId, UUID visitId) {
        return jdbc.query("SELECT c.visit_id, c.mode, c.policy_id, pp.payer_id, py.name AS payer_name, c.eligibility_ref, " +
                        "(c.copay_rule->>'patientPercent')::numeric AS copay_percent FROM visit_coverages c " +
                        "LEFT JOIN patient_policies pp ON pp.id = c.policy_id LEFT JOIN insurance_payers py ON py.id = pp.payer_id " +
                        "WHERE c.tenant_id = ? AND c.visit_id = ?",
                (rs, i) -> new CoverageRow(rs.getObject("visit_id", UUID.class), rs.getString("mode"), rs.getObject("policy_id", UUID.class),
                        rs.getObject("payer_id", UUID.class), rs.getString("payer_name"), rs.getString("eligibility_ref"), rs.getBigDecimal("copay_percent")),
                tenantId, visitId).stream().findFirst();
    }

    /** One row per visit: the latest decision replaces the earlier one. copayPercent null = self-pay. */
    void upsertCoverage(UUID tenantId, UUID visitId, String mode, UUID policyId, String eligibilityRef, BigDecimal copayPercent, UUID staffId) {
        String rule = copayPercent == null ? null : json.writeValueAsString(Map.of("type", "percent", "patientPercent", copayPercent));
        jdbc.update("INSERT INTO visit_coverages (tenant_id, visit_id, mode, policy_id, eligibility_ref, copay_rule, updated_by) VALUES (?,?,?,?,?,?::jsonb,?) " +
                        "ON CONFLICT (visit_id) DO UPDATE SET mode = EXCLUDED.mode, policy_id = EXCLUDED.policy_id, eligibility_ref = EXCLUDED.eligibility_ref, " +
                        "copay_rule = EXCLUDED.copay_rule, updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenantId, visitId, mode, policyId, eligibilityRef, rule, staffId);
    }
}
