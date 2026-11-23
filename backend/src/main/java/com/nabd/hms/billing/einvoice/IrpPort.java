package com.nabd.hms.billing.einvoice;

import java.time.Instant;
import java.util.UUID;

/**
 * FIN-011: the Invoice Registration Portal as the e-invoice worker sees it, reached through a GSP partner. There is NO production
 * adapter yet (the GSP, its sandbox and the clinic's GSP credentials are external: Needs external action), so no bean implements
 * this outside tests, the per-clinic switch cannot be turned on and no call is ever made. An adapter must:
 * <ul>
 * <li>call the GSP through shared.integration.ResilientHttpClient (timeouts, circuit breaker, refused inside a DB transaction);</li>
 * <li>read the clinic's GSP credentials from the tenant integration secret store (OPS-058 / T-314), never from code or config;</li>
 * <li>be idempotent per document: a repeat for an invoice the IRP already registered returns that IRN (IRP "duplicate IRN").</li>
 * </ul>
 */
public interface IrpPort {

    /** @param signedQr the signed QR payload exactly as returned (a JWT), printed as the invoice's QR code */
    record Accepted(String irn, String ackNo, Instant ackAt, String signedQr) {
    }

    /**
     * Registers one B2B invoice (schema INV-01 JSON, see {@link Inv01}).
     * @throws IrpRejectedException the IRP refused the document itself (validation): retrying the same payload cannot succeed
     * @throws RuntimeException anything else (timeout, 5xx, open circuit) is "IRP unavailable" and is retried with backoff
     */
    Accepted submit(UUID tenantId, String inv01Json);
}
