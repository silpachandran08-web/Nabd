package com.nabd.hms.auth.internal.token;

import com.nabd.hms.auth.internal.store.StaffDirectoryRepository;
import com.nabd.hms.auth.internal.store.CredentialRepository;
import com.nabd.hms.auth.internal.store.AuthSessionRepository;
import com.nabd.hms.auth.internal.store.AuthModels.Role;
import com.nabd.hms.auth.internal.store.AuthModels.Staff;
import com.nabd.hms.auth.internal.store.AuthModels.Tenant;
import com.nabd.hms.auth.internal.breakglass.BreakGlassPolicy;
import com.nabd.hms.auth.internal.breakglass.BreakGlassSession;
import com.nabd.hms.auth.internal.guard.LoginGuard;
import com.nabd.hms.auth.MfaPolicy;
import com.nabd.hms.auth.dto.MfaChallengeResponse;
import com.nabd.hms.auth.dto.MfaSetupRequiredResponse;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.config.AuthProperties;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenPopulation;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PLT-034 (split from AuthService): mints the staff access/refresh pair and the MFA challenge/setup tokens, and holds the
 * MFA policy check every first factor ends with. Always joins the caller's transaction; noRollbackFor keeps the caller's
 * lockout writes when a check here throws an ApiException.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class TokenService {

    public static final String ISSUER = TokenPopulation.STAFF.issuer();

    private final AuthSessionRepository sessionRepo;
    private final CredentialRepository credentials;
    private final StaffDirectoryRepository staffDir;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties props;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final LoginGuard guard;
    private final MfaPolicy mfaPolicy;
    private final Clock clock;

    TokenService(AuthSessionRepository sessionRepo, CredentialRepository credentials, StaffDirectoryRepository staffDir, JwtEncoder jwtEncoder, AuthProperties props, ObjectMapper objectMapper,
                 AuditService auditService, LoginGuard guard, MfaPolicy mfaPolicy, Clock clock) {
        this.sessionRepo = sessionRepo; this.credentials = credentials; this.staffDir = staffDir;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.guard = guard;
        this.mfaPolicy = mfaPolicy;
        this.clock = clock;
    }

    /** After a passed first factor (PIN or WhatsApp OTP): MFA setup token, MFA challenge, or the session itself. */
    public Object afterFirstFactor(Staff staff, String ip, String device, String userAgent) {
        if (!staff.mfaEnabled() && mfaRequiredByPolicy(staff)) {
            return issueMfaSetupRequired(staff);
        }
        if (staff.mfaEnabled()) {
            return issueMfaChallenge(staff);
        }
        return mintTokenPair(staff, UUID.randomUUID(), ip, device, userAgent);
    }

    public void enforceIpRateLimit(String ip) {
        int recent = credentials.countAttemptsFromIpSince(ip, Instant.now(clock).minus(1, ChronoUnit.MINUTES));
        if (recent >= props.rateLimitPerIpPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many attempts", "Try again shortly.");
        }
    }

    /**
     * NB-042: roles.mfa_required is the per-role opt-in. PLT-005 adds an invariant on top that no role
     * edit can clear: the built-in Owner role and any role (own or on loan) holding :approve or
     * :refundDiscount always requires MFA (see MfaPolicy).
     */
    public boolean mfaRequiredByPolicy(Staff staff) {
        if (staff.mfaResetRequired()) { // PLT-004: an owner reset forces enrolment at next sign-in
            return true;
        }
        Role role = staffDir.findRole(staff.roleId()).orElseThrow(() -> new IllegalStateException("role missing"));
        return role.mfaRequired() || privilegedByPolicy(staff, role);
    }

    public boolean privilegedByPolicy(Staff staff, Role role) {
        if (mfaPolicy.requiredForRole(role.builtIn(), flattenGrants(role.grantsJson()))) {
            return true;
        }
        return staffDir.findActiveDelegatedRoles(staff.id()).stream()
                .anyMatch(d -> mfaPolicy.requiredForRole(d.builtIn(), flattenGrants(d.grantsJson())));
    }

    public boolean tenantInactive(UUID tenantId) {
        return staffDir.findTenantById(tenantId).map(t -> com.nabd.hms.common.SessionRevocation.INACTIVE_TENANT_STATUSES.contains(t.status()))
                .orElse(true);
    }

    /** PLT-044: pending and active secrets share one AAD column, since confirmPendingMfa moves the value in SQL. */
    public static FieldCipher.Aad mfaAad(Staff staff) {
        return FieldCipher.Aad.of(staff.tenantId(), "staff", "mfa_secret", staff.id());
    }

    public MfaSetupRequiredResponse issueMfaSetupRequired(Staff staff) {
        String token = encode(mfaClaims(staff, "mfa_setup_required"), TokenType.MFA_SETUP);
        return new MfaSetupRequiredResponse(token, props.mfaChallengeTtlMinutes() * 60L);
    }

    public MfaChallengeResponse issueMfaChallenge(Staff staff) {
        String token = encode(mfaClaims(staff, "mfa_pending"), TokenType.MFA_PENDING);
        return new MfaChallengeResponse(token, "totp", props.mfaChallengeTtlMinutes() * 60L);
    }

    private JwtClaimsSet.Builder mfaClaims(Staff staff, String purpose) {
        Instant now = Instant.now(clock);
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(props.mfaChallengeTtlMinutes(), ChronoUnit.MINUTES))
                .subject(staff.id().toString())
                .claim("purpose", purpose)
                .claim("tenantId", staff.tenantId().toString());
    }

    private String encode(JwtClaimsSet.Builder claimsB, TokenType type) {
        type.stamp(claimsB, Audience.MFA);
        return jwtEncoder.encode(JwtEncoderParameters.from(claimsB.build())).getTokenValue();
    }

    public TokenPairResponse mintTokenPair(Staff staff, UUID familyId, String ip, String device, String userAgent) {
        return mintTokenPair(staff, familyId, ip, device, userAgent, null);
    }

    /** PLT-012: bg != null mints the read-only emergency token for that grant (see BreakGlassService). */
    public TokenPairResponse mintTokenPair(Staff staff, UUID familyId, String ip, String device, String userAgent, BreakGlassSession bg) {
        Role role = staffDir.findRole(staff.roleId())
                .orElseThrow(() -> new IllegalStateException("role missing for staff " + staff.id()));
        Tenant tenant = staffDir.findTenantById(staff.tenantId())
                .orElseThrow(() -> new IllegalStateException("tenant missing for staff " + staff.id()));
        List<String> permissions = new ArrayList<>(flattenGrants(role.grantsJson()));

        // NB-057: fold in any role currently on loan (e.g. covering a doctor's leave). Auditing
        // an expiry here, not on a scheduler, is what "the expiry is audited" means without a
        // background-job platform — see AuthRepository.expireStaleDelegations.
        for (UUID expiredId : staffDir.expireStaleDelegations(staff.id())) {
            auditService.record(staff.tenantId(), "system", null, "System", "System", null,
                    "staff.delegation_expired", "role_delegation", expiredId, null, null);
        }
        // PLT-012: the emergency token is base + read scope only; loans are not folded in.
        for (Role delegated : bg != null ? List.<Role>of() : staffDir.findActiveDelegatedRoles(staff.id())) {
            for (String p : flattenGrants(delegated.grantsJson())) {
                if (!permissions.contains(p)) {
                    permissions.add(p);
                }
            }
        }

        // NB-048: same lazy expire-and-audit shape as delegation above.
        for (UUID expiredId : staffDir.expireStaleBreakGlass(staff.id())) {
            auditService.record(staff.tenantId(), "system", null, "System", "System", null,
                    "auth.break_glass_expired", "break_glass_grant", expiredId, null, null);
        }
        if (bg != null) {
            for (String p : BreakGlassPolicy.BREAK_GLASS_GRANTS) {
                if (!permissions.contains(p)) {
                    permissions.add(p);
                }
            }
        }

        UUID sessionId = bg != null ? bg.sessionId() : com.nabd.hms.shared.UuidV7.next();
        Instant now = Instant.now(clock);
        Instant accessExp = bg != null ? bg.expiresAt() : now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet.Builder claimsB = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(accessExp)
                .subject(staff.id().toString())
                .claim("tenantId", staff.tenantId().toString())
                .claim("roleId", staff.roleId().toString())
                .claim("sid", sessionId.toString())
                .claim("permissions", permissions)
                // Display-only (ClinicNav's clinic-info card and signed-in-as footer) — never
                // used for authorization, so a stale name until next login is harmless.
                .claim("tenantName", tenant.name())
                .claim("tenantRegion", tenant.region())
                .claim("staffName", staff.name())
                .claim("roleName", role.name())
                // Landing only (lib/session landingPathFor): the owner's home is Owner Insights, but
                // their full grants would otherwise match the nursing rule first. From the staff
                // member's own role, never a delegated one.
                .claim("owner", role.builtIn())
                // PLT-003: PinChangeInterceptor refuses everything but pin change/logout while true
                .claim("pinChange", guard.pinChangeRequired(staff.id()));
        if (bg != null) {
            claimsB.claim("bg", bg.grantId().toString());
        }
        TokenType.ACCESS.stamp(claimsB, Audience.STAFF_API);
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(claimsB.build())).getTokenValue();

        String rawRefresh = OpaqueTokens.generate();
        Instant refreshExp = bg != null ? bg.expiresAt() : now.plus(props.refreshTokenTtlDays(), ChronoUnit.DAYS);
        sessionRepo.insertSession(sessionId, staff.tenantId(), staff.id(), familyId,
                OpaqueTokens.sha256Hex(rawRefresh), refreshExp, device, ip, userAgent, now);

        return new TokenPairResponse(accessToken, rawRefresh, Duration.between(now, accessExp).toSeconds(),
                Duration.between(now, refreshExp).toSeconds());
    }

    private List<String> flattenGrants(String grantsJson) {
        try {
            List<ModuleGrant> grants = objectMapper.readValue(grantsJson, new TypeReference<List<ModuleGrant>>() {
            });
            return GrantsFlattener.flatten(grants);
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed grants JSON", e);
        }
    }
}
