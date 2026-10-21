package com.nabd.hms.organization.dpa;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DpaRepository {

    record Acceptance(UUID id, String version, UUID signerId, String method, Instant acceptedAt) {
    }

    private final JdbcTemplate jdbc;

    DpaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String clinicName(UUID tenantId) {
        return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
    }

    String dataGate(UUID tenantId) {
        return jdbc.queryForObject("SELECT data_gate FROM tenants WHERE id = ?", String.class, tenantId);
    }

    boolean hasSubscription(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM master.subscriptions WHERE tenant_id = ?)", Boolean.class, tenantId));
    }

    Optional<Acceptance> find(String version) {
        return jdbc.query("SELECT id, dpa_version, signer_owner_id, method, accepted_at FROM dpa_acceptances WHERE dpa_version = ?", mapper(), version)
                .stream().findFirst();
    }

    Optional<Acceptance> latest() {
        return jdbc.query("SELECT id, dpa_version, signer_owner_id, method, accepted_at FROM dpa_acceptances ORDER BY accepted_at DESC LIMIT 1", mapper())
                .stream().findFirst();
    }

    /** False when another request accepted the same version first (unique tenant + version). */
    boolean insert(UUID id, UUID tenantId, String version, UUID signerId, String method, String key, byte[] pdfEnc, String sha256, String ip) {
        return jdbc.update("INSERT INTO dpa_acceptances (id, tenant_id, dpa_version, signer_owner_id, method, signed_pdf_key, signed_pdf, pdf_sha256, ip) " +
                "VALUES (?,?,?,?,?,?,?,?,?::inet) ON CONFLICT (tenant_id, dpa_version) DO NOTHING",
                id, tenantId, version, signerId, method, key, pdfEnc, sha256, ip) == 1;
    }

    Optional<byte[]> pdf(UUID id) {
        List<byte[]> r = jdbc.query("SELECT signed_pdf FROM dpa_acceptances WHERE id = ? AND signed_pdf IS NOT NULL", (rs, i) -> rs.getBytes(1), id);
        return r.stream().findFirst();
    }

    private static org.springframework.jdbc.core.RowMapper<Acceptance> mapper() {
        return (rs, i) -> new Acceptance(rs.getObject("id", UUID.class), rs.getString("dpa_version"),
                rs.getObject("signer_owner_id", UUID.class), rs.getString("method"), rs.getTimestamp("accepted_at").toInstant());
    }
}
