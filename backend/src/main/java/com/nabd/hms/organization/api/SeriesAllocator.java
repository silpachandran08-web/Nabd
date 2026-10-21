package com.nabd.hms.organization.api;

import java.util.UUID;

/**
 * FIN-006: the next statutory number of a series (invoice, credit note, receipt) for the facility bound to the current transaction.
 * Gapless because the number is taken with UPDATE ... RETURNING in the CALLER's transaction: the row lock is held until that
 * transaction commits, and a rollback gives the number back. Call it as late in the transaction as possible.
 */
public interface SeriesAllocator {

    enum Kind {
        INVOICE("invoice"), CREDIT_NOTE("credit_note"), RECEIPT("receipt");

        private final String column;

        Kind(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }
    }

    /** formatted = prefix (with the financial year) + zero-padded number, e.g. INV-25-26-000001. */
    record Allocation(UUID seriesId, long number, String formatted) {}

    Allocation allocate(Kind kind);
}
