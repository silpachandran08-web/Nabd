package com.nabd.hms.shared.security;

/** PLT-001: the only values the `aud` claim may take. */
public enum Audience {
    STAFF_API("staff-api"),
    PLATFORM_API("platform-api"),
    MFA("mfa"),
    PLATFORM_MFA("platform-mfa"),
    STEP_UP("step-up"),
    OWNER_SELECT("owner-select"),
    BRAND_SELECT("brand-select"), // PLT-023
    INTERNAL("internal"); // PLT-009: service identities (Patient Access Edge, Comms Gateway)

    private final String value;

    Audience(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
