package com.nabd.hms.billing.einvoice;

import java.util.List;

/** FIN-011: the IRP refused the document (its error codes, never the payload). Not retried until someone asks for a retry. */
public class IrpRejectedException extends RuntimeException {

    private final transient List<String> codes;

    public IrpRejectedException(List<String> codes, String message) {
        super(message);
        this.codes = List.copyOf(codes);
    }

    public List<String> codes() {
        return codes;
    }
}
