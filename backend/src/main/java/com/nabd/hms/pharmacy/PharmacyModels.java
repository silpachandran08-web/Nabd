package com.nabd.hms.pharmacy;

import java.math.BigDecimal;
import java.util.UUID;

class PharmacyModels {

    record PharmacyItemRow(UUID id, String code, String name, boolean isRx, String hsnCode, BigDecimal price,
                            BigDecimal taxRatePercent, int stockQty, boolean active) {
    }
}
