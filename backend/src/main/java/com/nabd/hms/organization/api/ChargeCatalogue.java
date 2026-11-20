package com.nabd.hms.organization.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PHL-041: the price master (charge_catalogue) for a module that sells its own items (lab tests and panels). The item keeps
 * only the head's id; name, price and tax code live on the head, so billing has one source. Every call joins the caller's
 * transaction and works on the clinic bound to it. A price change is logged like any other (FIN-016 price change log).
 */
public interface ChargeCatalogue {

    record Charge(UUID id, String code, String name, BigDecimal baseAmount, String taxCode, boolean active) {
    }

    /** Those of {@code codes} the price master already has (exact match). */
    Set<String> taken(Collection<String> codes);

    /** Adds a head; 409 duplicate-code when the code exists. An inactive head cannot be billed. */
    UUID add(UUID actorId, String code, String name, String category, BigDecimal baseAmount, String taxCode, boolean active);

    /** Changes a head's name, price, tax code and active flag; 404 when the clinic has no such head. */
    void update(UUID actorId, UUID chargeId, String name, BigDecimal baseAmount, String taxCode, boolean active);

    Map<UUID, Charge> find(Collection<UUID> ids);
}
