package com.nabd.hms.insurance.api;

import java.util.Map;
import java.util.Optional;

/**
 * FIN-036 (DEC-31): the port a market's claims middleware sits behind (India TPA / NHCX, DHA, NPHIES, Abu Dhabi). No adapter ships
 * with this story: FIN-037 onwards add one bean per market, and the insurance module never calls a middleware any other way.
 * An adapter addresses a payer by one key of the payer's {@code identifiers} (insurance_payers.identifiers).
 */
public interface ClaimsGateway {

    /** The region (regions.code) this adapter serves. */
    String jurisdiction();

    /** The key of a payer's identifiers this market's middleware knows the payer by, e.g. {@code nphies_id}. */
    String payerIdentifierKey();

    /** The payer's identifier for this market; empty when the payer master row does not carry it (the claim cannot be sent). */
    default Optional<String> payerIdentifier(Map<String, String> payerIdentifiers) {
        return Optional.ofNullable(payerIdentifiers.get(payerIdentifierKey())).filter(v -> !v.isBlank());
    }
}
