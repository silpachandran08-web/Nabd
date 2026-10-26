package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.api.StockCommands;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** PHL-003: the single writer of charge_catalogue.stock_qty and pharmacy_stock_movements (see StockWriteOwnershipTest). */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class StockCommandsImpl implements StockCommands {

    private final PharmacyRepository repo;
    private final TenantContext tenant;

    StockCommandsImpl(PharmacyRepository repo, TenantContext tenant) {
        this.repo = repo;
        this.tenant = tenant;
    }

    @Override
    public Optional<UUID> stockedItem(String chargeCode) {
        return repo.findStockedItemByCode(tenant.current(), chargeCode);
    }

    @Override
    public StockResult reserve(UUID itemId, int qty) {
        requirePositive(qty);
        int have = repo.lockItem(tenant.current(), itemId).orElseThrow(StockCommandsImpl::notFound).stockQty();
        if (have < qty) {
            throw insufficient(have, qty);
        }
        return result(itemId, have, have);
    }

    @Override
    public StockResult issue(UUID itemId, int qty, UUID invoiceId, String reason) {
        if (qty == 0) { // a zero-quantity line moves nothing
            int have = repo.lockItem(tenant.current(), itemId).orElseThrow(StockCommandsImpl::notFound).stockQty();
            return result(itemId, have, have);
        }
        requirePositive(qty);
        return move(itemId, -qty, "dispense", reason, invoiceId, null);
    }

    @Override
    public StockResult receive(UUID itemId, int qty, UUID actorId, String reason) {
        requirePositive(qty);
        return move(itemId, qty, "receipt", reason, null, actorId);
    }

    @Override
    public StockResult adjust(UUID itemId, int delta, UUID actorId, String reason) {
        if (delta == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-change", "Nothing to change", "The stock change is zero.");
        }
        return move(itemId, delta, "adjustment", reason, null, actorId);
    }

    @Override
    public StockResult returnIn(UUID itemId, int qty, UUID invoiceId, UUID actorId, String reason) {
        requirePositive(qty);
        return move(itemId, qty, "restock", reason, invoiceId, actorId);
    }

    /** One guarded UPDATE (takes the row lock; refuses to go below zero) plus the immutable movement row. */
    private StockResult move(UUID itemId, int delta, String kind, String reason, UUID invoiceId, UUID actorId) {
        UUID t = tenant.current();
        Optional<Integer> after = repo.applyDelta(t, itemId, delta);
        if (after.isEmpty()) {
            Optional<PharmacyModels.PharmacyItemRow> item = repo.findItem(t, itemId);
            throw item.isEmpty() ? notFound() : insufficient(item.get().stockQty(), -delta);
        }
        repo.insertMovement(t, itemId, kind, delta, after.get() - delta, after.get(), reason, actorId, invoiceId);
        return result(itemId, after.get() - delta, after.get());
    }

    private StockResult result(UUID itemId, int before, int after) {
        return new StockResult(itemId, before, after, repo.scheduleClassOf(tenant.current(), itemId).orElse(null));
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Quantity must be at least 1.");
        }
    }

    private static ApiException insufficient(int have, int want) {
        return new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Insufficient stock",
                "Only " + have + " in stock, not enough for " + want + " unit(s).");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested pharmacy item was not found.");
    }
}
