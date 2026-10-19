package com.nabd.hms.auth;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * PLT-005: who MUST use a second factor, whatever roles.mfa_required says. Required for the built-in
 * Owner role, any role whose grants include :refundDiscount or :approve (money and sign-off powers),
 * every platform operator and every owner account. app.auth.mfa.enforce-privileged=false (local/test
 * convenience only) is refused at boot under the prod profile.
 * shortcut: evaluated from the grants at every token mint (a JSON flatten), not cached per role version; add a cache if login latency shows it.
 */
@Component
public class MfaPolicy {

    public static final String PROPERTY = "app.auth.mfa.enforce-privileged";

    private final boolean enforce;

    MfaPolicy(Environment env) {
        this.enforce = env.getProperty(PROPERTY, Boolean.class, true);
        if (!enforce && env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(PROPERTY + " must not be false in the prod profile (PLT-005)");
        }
    }

    /** The grant rule alone, independent of the enforce flag: role edits may never clear MFA on such a role. */
    public static boolean privilegedGrants(Collection<String> permissions) {
        return permissions.stream().anyMatch(p -> p.endsWith(":approve") || p.endsWith(":refundDiscount"));
    }

    public static boolean privilegedRole(boolean builtIn, Collection<String> permissions) {
        return builtIn || privilegedGrants(permissions);
    }

    public boolean enforced() {
        return enforce;
    }

    public boolean requiredForRole(boolean builtIn, Collection<String> permissions) {
        return enforce && privilegedRole(builtIn, permissions);
    }

    /** Every operator role, super_admin included: no exemption exists. */
    public boolean requiredForOperator() {
        return enforce;
    }

    // NFR-06: WhatsApp OTP as an allowed second factor waits on a founder decision; TOTP is the only factor today.
    public boolean requiredForOwner() {
        return enforce;
    }
}
