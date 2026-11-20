package com.nabd.hms.pharmacy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * PLT-036: charge_catalogue rows are created and changed by one module (billing). Pharmacy items are charge_catalogue rows; this is
 * a port the billing module implements, so pharmacy keeps its item rules and audit while the statements on the shared table run in
 * the owning module (and pharmacy does not depend on billing). All run in the caller's transaction. The one column pharmacy still
 * writes itself is the cached balance stock_qty (PHL-003 AC1, `owns_columns` in docs/architecture/table-ownership.yaml).
 */
public interface ItemCatalogueWriter {

    /** The item row without its stock balance (pharmacy sets stock_qty itself); is_rx is then set by the profile trigger. Returns the new id. */
    UUID insertItem(UUID tenantId, String code, String name, String category, BigDecimal price, BigDecimal taxRatePercent,
                    boolean isRx, String hsnCode, LocalDate effectiveFrom);

    /** Name, price, tax and HSN of a stocked item (stock_qty is never written here); rows changed. */
    int updateItem(UUID tenantId, UUID id, String name, BigDecimal price, BigDecimal taxRatePercent, String hsnCode);

    /** The two single-column item flags; a null argument leaves its column as it is. */
    void patchItem(UUID tenantId, UUID id, Boolean active, String drugId);
}
