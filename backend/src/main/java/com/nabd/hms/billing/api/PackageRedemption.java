package com.nabd.hms.billing.api;

import java.util.UUID;

/**
 * PLT-035: the one way billing code redeems a package session (e.g. at checkout). Implemented by the packages module, which owns the
 * package tables; nothing outside it writes them. Same path as the desk's Redeem button: the instance row is locked, so two redeems
 * of the last session let exactly one through (400 no-sessions-remaining for the other). Runs in the caller's transaction.
 */
public interface PackageRedemption {

    record Redeemed(UUID instanceId, UUID instanceItemId, int quantityConsumed, int quantityTotal, String instanceStatus) {
    }

    Redeemed redeem(UUID tenantId, UUID staffId, UUID instanceItemId);
}
