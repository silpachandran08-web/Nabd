package com.nabd.hms.auth;

import com.nabd.hms.auth.api.SecondSignOff;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
class SecondSignOffService implements SecondSignOff {

    private final AuthRepository repo;
    private final LoginGuard guard;
    private final PasswordEncoder pinEncoder;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    SecondSignOffService(AuthRepository repo, LoginGuard guard, PasswordEncoder pinEncoder, ObjectMapper objectMapper, JdbcTemplate jdbc,
                         TenantContext tenant) {
        this.repo = repo;
        this.guard = guard;
        this.pinEncoder = pinEncoder;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Signer> holders(String permission, UUID exceptStaffId) {
        UUID t = tenant.current();
        Map<UUID, Set<String>> loaned = new HashMap<>();
        jdbc.query("SELECT d.staff_id, r.grants::text FROM role_delegations d JOIN roles r ON r.id = d.delegated_role_id " +
                        "WHERE r.tenant_id = ? AND d.revoked_at IS NULL AND d.starts_at <= now() AND d.expires_at > now()",
                rs -> {
                    loaned.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>()).addAll(flatten(rs.getString(2)));
                }, t);
        return jdbc.query("SELECT s.id, s.name, r.grants::text FROM staff s JOIN roles r ON r.id = s.role_id " +
                                "WHERE s.tenant_id = ? AND s.status = 'active' AND s.pin_hash IS NOT NULL AND s.id <> ? ORDER BY s.name, s.id",
                        (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), flatten(rs.getString(3))}, t, exceptStaffId)
                .stream()
                .filter(r -> ((List<?>) r[2]).contains(permission) || loaned.getOrDefault((UUID) r[0], Set.of()).contains(permission))
                .map(r -> new Signer((UUID) r[0], (String) r[1])).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class) // the failed attempt must survive the caller's rollback
    public Confirmation confirm(UUID staffId, String pin, String permission) {
        UUID t = tenant.current();
        AuthModels.Staff staff = repo.findStaffById(staffId).filter(s -> t.equals(s.tenantId()) && "active".equals(s.status())).orElse(null);
        boolean holds = staff != null && (repo.findRole(staff.roleId()).map(r -> flatten(r.grantsJson()).contains(permission)).orElse(false)
                || repo.findActiveDelegatedRoles(staff.id()).stream().anyMatch(r -> flatten(r.grantsJson()).contains(permission)));
        if (!holds) {
            return new Confirmation(Outcome.NOT_PERMITTED, null);
        }
        guard.enforceLockout(staffId);
        if (staff.pinHash() == null || pin == null || !pinEncoder.matches(pin, staff.pinHash())) {
            guard.recordAttempt(t, staffId, staff.email(), "step_up", false);
            return new Confirmation(Outcome.WRONG_PIN, null);
        }
        guard.recordAttempt(t, staffId, staff.email(), "step_up", true);
        return new Confirmation(Outcome.CONFIRMED, new Signer(staffId, staff.name()));
    }

    private List<String> flatten(String grantsJson) {
        try {
            return GrantsFlattener.flatten(objectMapper.readValue(grantsJson, new TypeReference<List<ModuleGrant>>() { }));
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }
}
