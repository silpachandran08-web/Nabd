package com.nabd.hms.shared.integration;

/** OPS-012: an external call (HTTP, SMTP) was attempted while a DB transaction was open on this thread. A slow
 * provider would then hold a pooled connection for the whole call; with enough of them the API runs out of
 * connections. Fix the caller: commit intent, call with no transaction open, record the result in a short one. */
public class NoTransactionAllowedException extends IllegalStateException {
    public NoTransactionAllowedException(String message) {
        super(message);
    }
}
