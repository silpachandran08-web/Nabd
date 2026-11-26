package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.ProxyAccess;
import com.nabd.hms.shared.files.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PLT-047: the two decisions that hand a record over, kept in one place and asked again at every step (intake, build, download):
 * who may ask for whose data, and whether a stored package may still be served. The package is encrypted with the clinic's data
 * key and bound to its request (AAD), so a blob copied to another request or clinic does not decrypt.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class DsrGate {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final ProxyAccess proxies;
    private final ObjectStorage storage;
    private final FieldCipher cipher;

    DsrGate(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, ProxyAccess proxies, ObjectStorage storage, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.proxies = proxies;
        this.storage = storage;
        this.cipher = cipher;
    }

    /**
     * The patient themself (an adult), or the guardian of a minor whose relation is active with FULL scope. A BOOK_ONLY or
     * BOOK_AND_RECORDS proxy, a pending (unapproved) one and a revoked one are refused, and so is any proxy of an adult: an adult asks
     * for their own data (decision recorded in the PLT-047 report; relax here only with the DPO's sign-off). A minor never asks for
     * themself: a child is registered on the guardian's phone, so the person behind that phone is the guardian. Both must be active
     * patients of the bound clinic (RLS hides every other clinic's rows, so a foreign id is simply not found).
     */
    public boolean mayRequestFor(UUID requester, UUID subject) {
        UUID tenant = tenantContext.current();
        Integer active = jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ? AND id IN (?, ?) AND status = 'active'", Integer.class, tenant, requester, subject);
        if (requester.equals(subject)) {
            return active == 1 && !minor(subject);
        }
        return active == 2 && minor(subject)
                && proxies.relationFor(requester, subject).filter(a -> "active".equals(a.status()) && "FULL".equals(a.scope())).isPresent();
    }

    /** Under 18 on the clinic's date today. */
    public boolean minor(UUID patient) {
        UUID tenant = tenantContext.current();
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT dob > ?::date - INTERVAL '18 years' FROM patients WHERE tenant_id = ? AND id = ?",
                Boolean.class, clock.today(tenant).toString(), tenant, patient));
    }

    void store(UUID requestId, UUID fileId, byte[] archive) {
        UUID tenant = tenantContext.current();
        storage.put(ObjectStorage.Bucket.CLEAN, key(tenant, fileId), cipher.encrypt(archive, aad(tenant, requestId)));
    }

    /**
     * The package of a completed request, decrypted, for the person it was built for. Empty when the request is not this patient's
     * and this recipient's, is not completed, or the recipient may no longer ask for this patient (guardian link ended since).
     */
    public Optional<byte[]> packageFor(UUID requestId, UUID patientId, UUID recipientId) {
        UUID tenant = tenantContext.current();
        Map<String, Object> r = jdbc.queryForList("SELECT export_file_id FROM dsr_requests WHERE tenant_id = ? AND id = ? AND patient_id = ? AND requester_patient_id = ? "
                + "AND type = 'access' AND status = 'completed' AND export_file_id IS NOT NULL", tenant, requestId, patientId, recipientId).stream().findFirst().orElse(null);
        if (r == null || !mayRequestFor(recipientId, patientId)) {
            return Optional.empty();
        }
        return storage.get(ObjectStorage.Bucket.CLEAN, key(tenant, (UUID) r.get("export_file_id"))).map(enc -> cipher.decrypt(enc, aad(tenant, requestId)));
    }

    private static String key(UUID tenant, UUID fileId) {
        return "tenant/" + tenant + "/dsr/" + fileId + ".zip.enc";
    }

    private static FieldCipher.Aad aad(UUID tenant, UUID requestId) {
        return FieldCipher.Aad.of(tenant, "dsr_requests", "export", requestId);
    }
}
