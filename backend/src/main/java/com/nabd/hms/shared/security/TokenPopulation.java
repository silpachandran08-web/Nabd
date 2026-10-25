package com.nabd.hms.shared.security;

import java.util.List;

/**
 * PLT-009: who a token was issued to. Each population has its own issuer and its own signing keys, so a token minted for
 * one can never verify for another. The issuer is the discriminator the decoder reads before it picks the key set.
 */
public enum TokenPopulation {
    STAFF("staff"), OWNER("owner"), PLATFORM("platform"), INTERNAL("internal");

    /** The single issuer every token had before PLT-009; the encoder re-stamps it with the right population issuer. */
    public static final String LEGACY_ISSUER = "https://api.nabd.health";

    private final String slug;

    TokenPopulation(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public String issuer() {
        return LEGACY_ISSUER + "/" + slug;
    }

    public static TokenPopulation fromIssuer(String iss) {
        for (TokenPopulation p : values()) if (p.issuer().equals(iss)) return p;
        return null;
    }

    public static TokenPopulation fromSlug(String slug) {
        for (TokenPopulation p : values()) if (p.slug.equals(slug)) return p;
        return null;
    }

    /** Population implied by the audience of a token minted without an explicit issuer (tests, legacy callers). */
    public static TokenPopulation forAudience(List<String> aud) {
        if (aud == null) return STAFF;
        if (aud.contains(Audience.PLATFORM_API.value()) || aud.contains(Audience.PLATFORM_MFA.value())) return PLATFORM;
        if (aud.contains(Audience.OWNER_SELECT.value())) return OWNER;
        if (aud.contains(Audience.INTERNAL.value())) return INTERNAL;
        return STAFF;
    }
}
