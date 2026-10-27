package com.nabd.hms.billing.gateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.shared.secrets.SecretResolver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** FIN-019: payment_gateway_accounts. Every method is its own short transaction so the gateway call in between is made outside any. */
@Component
class GatewayAccountStore {

    record Account(UUID id, String provider, String status, String secretRef, Instant connectedAt, String lastTestStatus, Instant lastTestAt) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final SecretResolver secrets;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<GatewaySecrets> vault;

    GatewayAccountStore(JdbcTemplate jdbc, TenantContext tenantContext, SecretResolver secrets, AuditService audit, ActorDirectory actors, ObjectMapper json,
                        org.springframework.beans.factory.ObjectProvider<GatewaySecrets> vault) {
        this.vault = vault;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.secrets = secrets;
        this.audit = audit;
        this.actors = actors;
        this.json = json;
    }

    /** The bound tenant, after checking the clinic has no live account (one gateway per clinic). */
    @Transactional
    UUID tenantWithoutLiveAccount() {
        UUID tenantId = tenantContext.current();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM payment_gateway_accounts WHERE tenant_id = ? AND status <> 'disabled')", Boolean.class, tenantId))) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CONNECTED", "Already connected", "This clinic already has a payment gateway. Disconnect it first.");
        }
        return tenantId;
    }

    @Transactional
    Account insert(String provider, String secretRef, UUID staffId, String ip) {
        UUID tenantId = tenantContext.current();
        UUID id = jdbc.queryForObject("INSERT INTO payment_gateway_accounts (tenant_id, provider, secret_ref, status, connected_by) VALUES (?,?,?,'verified',?) RETURNING id",
                UUID.class, tenantId, provider, secretRef, staffId);
        record(tenantId, staffId, ip, "billing.gateway_connected", id, null, Map.of("provider", provider));
        return get(id);
    }

    @Transactional(readOnly = true)
    Optional<Account> current() {
        return jdbc.query("SELECT id, provider, status, secret_ref, connected_at, last_test_status, last_test_at FROM payment_gateway_accounts " +
                "WHERE tenant_id = ? AND status <> 'disabled'", (rs, i) -> map(rs), tenantContext.current()).stream().findFirst();
    }

    @Transactional(readOnly = true)
    Account get(UUID id) {
        return jdbc.query("SELECT id, provider, status, secret_ref, connected_at, last_test_status, last_test_at FROM payment_gateway_accounts WHERE tenant_id = ? AND id = ?",
                (rs, i) -> map(rs), tenantContext.current(), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "gateway-account-not-found", "Gateway account not found", "No such payment gateway account."));
    }

    /** Reads the keys from the secret store in a short transaction (the resolver needs the bound tenant); the caller makes the HTTP call afterwards. */
    @Transactional(readOnly = true)
    PaymentGateway.Credentials credentials(Account a) {
        GatewaySecrets w = vault.getIfAvailable();
        String raw = (w != null ? w.get(tenantContext.current(), a.secretRef()) : secrets.tenant(a.secretRef())).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_VERIFIED", "Keys not found", "The stored keys could not be read. Reconnect the gateway."));
        try {
            Map<?, ?> m = json.readValue(raw, Map.class);
            return new PaymentGateway.Credentials((String) m.get("keyId"), (String) m.get("keySecret"));
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_VERIFIED", "Keys not readable", "The stored keys could not be read. Reconnect the gateway.");
        }
    }

    @Transactional
    void markTest(UUID id) {
        jdbc.update("UPDATE payment_gateway_accounts SET last_test_at = now() WHERE tenant_id = ? AND id = ?", tenantContext.current(), id);
    }

    /** Returns the secret name to delete, or null when the account was already disabled (DELETE is idempotent). */
    @Transactional
    String disable(UUID id, UUID staffId, String ip) {
        UUID tenantId = tenantContext.current();
        Account a = get(id);
        if ("disabled".equals(a.status())) return null;
        jdbc.update("UPDATE payment_gateway_accounts SET status = 'disabled', disabled_at = now() WHERE tenant_id = ? AND id = ?", tenantId, id);
        record(tenantId, staffId, ip, "billing.gateway_disconnected", id, Map.of("provider", a.provider()), null);
        return a.secretRef();
    }

    @Transactional(readOnly = true)
    UUID tenant() {
        return tenantContext.current();
    }

    private void record(UUID tenantId, UUID staffId, String ip, String action, UUID id, Object before, Object after) {
        String[] a = actors.findActor(tenantId, staffId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, a[0], a[1], ip, action, "payment_gateway_account", id, before, after);
    }

    private static Account map(java.sql.ResultSet rs) throws java.sql.SQLException {
        var t = rs.getTimestamp("last_test_at");
        return new Account(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("status"), rs.getString("secret_ref"),
                rs.getTimestamp("connected_at").toInstant(), rs.getString("last_test_status"), t == null ? null : t.toInstant());
    }
}
