package com.nabd.hms.billing;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the billing module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class BillingPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "billing";
    }

    @Override
    public List<Table> tables() {
        Table invoices = Table.of("invoices");
        return List.of(invoices,
                Table.child("invoice_line_items", "invoice_id", invoices),
                Table.child("invoice_discounts", "invoice_id", invoices),
                Table.child("invoice_payments", "invoice_id", invoices).omit("idempotency_key", "gateway_ref", "payer_ref"),
                Table.child("credit_notes", "invoice_id", invoices),
                Table.child("refund_requests", "invoice_id", invoices),
                Table.child("invoice_write_offs", "invoice_id", invoices),
                Table.of("claim_packs"),
                Table.of("dues_reminders").omit("message_ref"));
    }
}
