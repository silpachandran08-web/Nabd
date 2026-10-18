package com.nabd.hms.shared.security;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;

import java.util.Set;

/** PLT-001: the only values the `typ` claim may take. Only ACCESS opens the resource server. */
public enum TokenType {
    ACCESS("access", Audience.STAFF_API, Audience.PLATFORM_API),
    MFA_PENDING("mfa_pending", Audience.MFA, Audience.PLATFORM_MFA),
    MFA_SETUP("mfa_setup", Audience.MFA),
    STEP_UP("step_up", Audience.STEP_UP),
    OWNER_SELECT("owner_select", Audience.OWNER_SELECT);

    public static final String CLAIM = "typ";

    private final String claim;
    private final Set<Audience> allowed;

    TokenType(String claim, Audience... allowed) {
        this.claim = claim;
        this.allowed = Set.of(allowed);
    }

    public String claim() {
        return claim;
    }

    public boolean allows(String aud) {
        return allowed.stream().anyMatch(a -> a.value().equals(aud));
    }

    /** Stamps typ + aud on a token under construction; every mint site calls this. */
    public JwtClaimsSet.Builder stamp(JwtClaimsSet.Builder b, Audience aud) {
        if (!allowed.contains(aud)) {
            throw new IllegalArgumentException(aud + " is not an audience of " + this);
        }
        return b.claim(CLAIM, claim).audience(java.util.List.of(aud.value()));
    }
}
