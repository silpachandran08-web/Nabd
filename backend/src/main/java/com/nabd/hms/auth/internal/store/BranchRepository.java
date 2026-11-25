package com.nabd.hms.auth.internal.store;

import com.nabd.hms.auth.dto.BranchSummary;
import com.nabd.hms.common.SessionRevocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PLT-023: brand_logins has no tenant and the app role holds no privilege on it. Everything here goes through the SECURITY
 * DEFINER functions of V3680, which answer for the caller's own brand only (ids, names and statuses; never a credential).
 */
@Repository
public class BranchRepository {

    /** staffId is null for a branch the owner account has not entered yet; ownerId is set only on the owner account's rows. */
    public record Branch(UUID tenantId, UUID staffId, String name, String branchCode, String tenantStatus, String staffStatus,
                         UUID brandLoginId, UUID ownerId) {

        /** Can be entered now: the clinic is not offboarded and the person's staff row there is active. */
        public boolean open() {
            return !SessionRevocation.CLOSED_TENANT_STATUS.equals(tenantStatus) && "active".equals(staffStatus);
        }

        public BranchSummary summary() {
            return new BranchSummary(tenantId, name, branchCode);
        }
    }

    /** status: LINKED, or the reason nothing was linked (see link_brand_staff). */
    public record LinkResult(String status, UUID brandLoginId) {
    }

    /** A staff row in another branch of the brand that is the same person (same email or mobile number). */
    public record LinkCandidate(UUID tenantId, String branchName, UUID staffId, String staffName, String roleName, boolean linked) {
    }

    private final JdbcTemplate jdbc;

    BranchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every branch of the person behind this staff row (their own included), whatever its status; empty when not linked. */
    public List<Branch> branchesOf(UUID staffId, UUID tenantId) {
        return jdbc.query("SELECT * FROM brand_login_branches(?, ?)", (rs, i) -> new Branch(
                rs.getObject("o_tenant_id", UUID.class), rs.getObject("o_staff_id", UUID.class), rs.getString("o_name"),
                rs.getString("o_branch_code"), rs.getString("o_tenant_status"), rs.getString("o_staff_status"),
                rs.getObject("o_brand_login_id", UUID.class), rs.getObject("o_owner_id", UUID.class)), staffId, tenantId);
    }

    /** True exactly once per token id. */
    public boolean consumeToken(UUID jti) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT consume_brand_login_token(?)", Boolean.class, jti));
    }

    /** Empty unless ownerStaffId is the owner account of the brand, signed in to ownerTenantId. */
    public List<LinkCandidate> linkCandidates(UUID ownerStaffId, UUID ownerTenantId, UUID staffId) {
        return jdbc.query("SELECT * FROM brand_staff_link_candidates(?, ?, ?)", (rs, i) -> new LinkCandidate(
                rs.getObject("o_tenant_id", UUID.class), rs.getString("o_branch_name"), rs.getObject("o_staff_id", UUID.class),
                rs.getString("o_staff_name"), rs.getString("o_role_name"), rs.getBoolean("o_linked")), ownerStaffId, ownerTenantId, staffId);
    }

    public LinkResult link(UUID ownerStaffId, UUID ownerTenantId, List<UUID> staffIds, UUID stepUpId, String ip) {
        String ids = staffIds.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return jdbc.queryForObject("SELECT o_status, o_brand_login_id FROM link_brand_staff(?, ?, ?::uuid[], ?, ?)",
                (rs, i) -> new LinkResult(rs.getString(1), rs.getObject(2, UUID.class)), ownerStaffId, ownerTenantId, ids, stepUpId, ip);
    }
}
