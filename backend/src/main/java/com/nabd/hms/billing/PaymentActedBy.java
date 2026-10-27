package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.PaymentRequest;
import com.nabd.hms.patient.api.ProxyAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** IDC-011: stores who paid on the patient's behalf (a caregiver paying a link, or the desk's "on behalf of") on the payment row, and audits it. */
@Component
class PaymentActedBy {

    private final JdbcTemplate jdbc;
    private final ProxyAccess proxy;

    PaymentActedBy(JdbcTemplate jdbc, ProxyAccess proxy) {
        this.jdbc = jdbc;
        this.proxy = proxy;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void stamp(UUID tenantId, UUID paymentId, UUID invoicePatientId, PaymentRequest req) {
        if (req.actedByPatientId() == null) {
            return;
        }
        ProxyAccess.ActedBy by = proxy.onBehalf(req.actedByPatientId(), req.actedByRelation(), invoicePatientId);
        jdbc.update("UPDATE invoice_payments SET acted_by_patient_id = ?, acted_by_relation = ? WHERE tenant_id = ? AND id = ?", by.patientId(), by.relation(), tenantId, paymentId);
        proxy.audit(by, "payment", "invoice_payment", paymentId);
    }
}
