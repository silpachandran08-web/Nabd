package com.nabd.hms.billing;

import java.math.BigDecimal;
import java.util.Set;

/** FIN-001: the one place invoice status is derived from paid vs total. */
final class InvoiceStatus {

    /** Terminal states a payment or totals change never moves an invoice out of. */
    static final Set<String> CLOSED = Set.of("void", "cancelled", "refunded");

    private InvoiceStatus() {
    }

    static String derive(String current, BigDecimal total, BigDecimal paid) {
        if (CLOSED.contains(current)) {
            return current;
        }
        if (total.signum() > 0 && paid.compareTo(total) >= 0) {
            return "paid";
        }
        return paid.signum() > 0 ? "partial" : "unpaid";
    }
}
