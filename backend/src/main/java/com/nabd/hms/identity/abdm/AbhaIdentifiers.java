package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.PatientSummaries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IDC-017: patient_identifiers for ABHA (RLS). The value is AES-GCM sealed with an AAD bound to its row; lookups use an HMAC
 * blind index (keyed per tenant and id type), so equal ids collide on the unique key without the value ever being stored in clear.
 * Transactional on purpose and separate from AbhaService, which makes the remote ABDM calls outside any transaction.
 */
@Component
@Transactional
class AbhaIdentifiers {

    static final String NUMBER = "ABHA_NUMBER";
    static final String ADDRESS = "ABHA_ADDRESS";

    record Linked(UUID patientId, String last4) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final FieldCipher cipher;
    private final PatientSummaries patients;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final byte[] key;

    AbhaIdentifiers(JdbcTemplate jdbc, TenantContext tenantContext, FieldCipher cipher, PatientSummaries patients, AuditService audit, ActorDirectory actors,
                    @Value("${app.identity.blind-index-key:${app.crypto.key:}}") String blindKey) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.patients = patients;
        this.audit = audit;
        this.actors = actors;
        this.key = blindKey == null || blindKey.isBlank() ? new byte[0] : blindKey.getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical form: ABHA number = 14 digits, address = lower case. */
    static String normalise(String idType, String value) {
        return NUMBER.equals(idType) ? value.replaceAll("\\D", "") : value.strip().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    PatientSummaries.Summary requirePatient(UUID patientId) {
        return patients.find(patientId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such patient."));
    }

    /** Throws 409 abha-linked-elsewhere (with the existing patient, for the merge prompt) when another patient of this clinic holds the id. */
    @Transactional(readOnly = true)
    void requireNotLinkedElsewhere(String idType, String value, UUID patientId) {
        Optional<UUID> owner = jdbc.query("SELECT patient_id FROM patient_identifiers WHERE tenant_id = ? AND id_type = ? AND blind_index = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantContext.current(), idType, blindIndex(idType, value)).stream().findFirst();
        if (owner.isPresent() && !owner.get().equals(patientId)) {
            PatientSummaries.Summary other = patients.find(owner.get()).orElse(null);
            Map<String, Object> extras = other == null ? Map.of() : Map.of("existingPatient", Map.of("id", other.id(), "name", other.name(), "mrn", other.mrn()),
                    "mergeFlow", "/v1/patients/duplicates");
            throw new ApiException(HttpStatus.CONFLICT, "abha-linked-elsewhere", "ABHA already linked",
                    "This ABHA is already linked to another patient of this clinic; review that record and merge if it is the same person.", extras);
        }
    }

    /** Stores (or refreshes) the verified id for the patient. Returns the last four characters shown in the UI. */
    String link(UUID patientId, String idType, String value, String verifiedVia) {
        requireNotLinkedElsewhere(idType, value, patientId);
        UUID tenant = tenantContext.current();
        String norm = normalise(idType, value);
        String bi = blindIndex(idType, norm);
        String last4 = norm.substring(Math.max(0, norm.length() - 4));
        jdbc.update("DELETE FROM patient_identifiers WHERE tenant_id = ? AND patient_id = ? AND id_type = ? AND blind_index <> ?", tenant, patientId, idType, bi);
        int refreshed = jdbc.update("UPDATE patient_identifiers SET verified_via = ?, verified_at = now() WHERE tenant_id = ? AND id_type = ? AND blind_index = ?",
                verifiedVia, tenant, idType, bi);
        if (refreshed == 0) {
            UUID id = UUID.randomUUID();
            byte[] sealed = cipher.encrypt(norm.getBytes(StandardCharsets.UTF_8), FieldCipher.Aad.of(tenant, "patient_identifiers", "value_enc", id));
            try {
                jdbc.update("INSERT INTO patient_identifiers (id, tenant_id, patient_id, id_type, blind_index, value_enc, last4, verified_via) VALUES (?,?,?,?,?,?,?,?)",
                        id, tenant, patientId, idType, bi, sealed, last4, verifiedVia);
            } catch (DuplicateKeyException race) { // two desks verified the same id at once: the first one wins, the other is told who holds it
                requireNotLinkedElsewhere(idType, value, patientId);
            }
        }
        return last4;
    }

    void auditVerified(UUID staffId, String ip, UUID patientId, String idType, String last4, String via) {
        UUID tenant = tenantContext.current();
        ActorDirectory.Actor a = actors.findActor(tenant, staffId).orElse(null);
        audit.record(tenant, "staff", staffId, a == null ? "Staff" : a.name(), a == null ? "Staff" : a.role(), ip, "identity.abha_verified", "patient", patientId,
                null, Map.of("idType", idType, "last4", last4, "via", via)); // never the number itself
    }

    private String blindIndex(String idType, String value) {
        if (key.length == 0) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identifier-key-not-configured", "Identifier key missing", "Identifier storage is not configured.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((tenantContext.current() + "|" + idType + "|" + normalise(idType, value)).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
