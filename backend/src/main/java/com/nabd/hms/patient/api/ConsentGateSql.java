package com.nabd.hms.patient.api;

/**
 * IDC-006: the one SQL gate for "may this patient's data be processed". Default-deny clinics (tenants.consent_default_deny)
 * need the newest data_processing row to be a grant, or an emergency treatment basis recorded at the desk (a 'treatment'
 * row whose evidence names basis=emergency, newest of its kind). Legacy clinics keep the old rule: only a withdrawal blocks.
 * Args are SQL expressions of the enclosing query (e.g. "patients.id", "patients.tenant_id").
 */
public final class ConsentGateSql {

    private ConsentGateSql() {
    }

    private static String newest(String alias, String p, String t, String type, String select) {
        return "(SELECT " + select + " FROM consents " + alias + " WHERE " + alias + ".tenant_id = " + t + " AND " + alias + ".patient_id = " + p
                + " AND " + alias + ".consent_type = '" + type + "' ORDER BY " + alias + ".created_at DESC, " + alias + ".id DESC LIMIT 1)";
    }

    public static String gate(String p, String t) {
        return "AND CASE WHEN (SELECT cg_t.consent_default_deny FROM tenants cg_t WHERE cg_t.id = " + t + ") THEN ("
                + "COALESCE(" + newest("cg_d", p, t, "data_processing", "cg_d.withdrawn_at IS NULL") + ", false) OR "
                + "COALESCE(" + newest("cg_e", p, t, "treatment", "cg_e.withdrawn_at IS NULL AND cg_e.evidence->>'basis' = 'emergency'") + ", false)) "
                + "ELSE NOT EXISTS (SELECT 1 FROM consents cg_w WHERE cg_w.tenant_id = " + t + " AND cg_w.patient_id = " + p
                + " AND cg_w.consent_type = 'data_processing' AND cg_w.withdrawn_at IS NOT NULL) END ";
    }
}
