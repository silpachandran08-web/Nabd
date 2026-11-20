package com.nabd.hms.organization.api;

/**
 * PHL-032: a precondition on issuing an invoice that does not belong to billing or the numbering itself. Today: a pharmacy-only
 * tenant may not issue invoices without its drug licence number. Called inside the caller's tenant transaction.
 */
public interface InvoicingGate {

    /** @throws com.nabd.hms.common.ApiException 422 DRUG_LICENCE_MISSING when the tenant may not invoice yet */
    void requireReady();
}
