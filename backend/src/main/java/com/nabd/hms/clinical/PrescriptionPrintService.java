package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionPrintHeaderResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** DOC-N1: read-only header data for printing a signed prescription. Only a signed prescription can be printed. */
@Service
public class PrescriptionPrintService {

    private final PrescriptionPrintRepository repo;
    private final TenantContext tenantContext;

    PrescriptionPrintService(PrescriptionPrintRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public PrescriptionPrintHeaderResponse header(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        PrescriptionPrintRepository.Header h = repo.findHeader(tenantId, queueEntryId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        if (!"signed".equals(h.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "prescription-not-signed", "Prescription not signed",
                    "Only a signed prescription can be printed.");
        }
        // The signer is the prescriber of record; fall back to the visit's doctor if no signing audit row exists.
        UUID prescriberId = h.signerId() != null ? h.signerId() : h.doctorId();
        PrescriptionPrintRepository.Person who = prescriberId == null ? null : repo.findPerson(tenantId, prescriberId).orElse(null);
        String reg = who == null || who.registrationNo() == null || who.registrationNo().isBlank() ? null : who.registrationNo();
        return new PrescriptionPrintHeaderResponse(h.prescriptionId(), h.signedAt(), h.clinicName(), h.clinicRegion(),
                h.clinicPhone(), prescriberId, who == null ? null : who.name(), reg);
    }
}
