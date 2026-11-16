package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionPrintHeaderResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.Qr;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** DOC-N1: read-only header data for printing a signed prescription. Only a signed prescription can be printed. */
@Service
public class PrescriptionPrintService {

    private final PrescriptionPrintRepository repo;
    private final TenantContext tenantContext;
    private final DrugCoding coding;
    private final RxDocumentService rxDocuments;

    PrescriptionPrintService(PrescriptionPrintRepository repo, TenantContext tenantContext, DrugCoding coding, RxDocumentService rxDocuments) {
        this.coding = coding;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.rxDocuments = rxDocuments;
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
        // CLN-005: signed_by is NOT NULL on every signed row (V1221 CHECK); the number printed is the one signed under.
        UUID prescriberId = h.signerId();
        PrescriptionPrintRepository.Person who = prescriberId == null ? null : repo.findPerson(tenantId, prescriberId).orElse(null);
        String reg = h.signerRegistrationNo() == null || h.signerRegistrationNo().isBlank() ? null : h.signerRegistrationNo();
        // PHL-013 / CLN-023: the one stored signed token (ids, times, the registration number and the signature only), printed as the
        // public verify link the pharmacy scanner and any phone read.
        PrescriptionPrintRepository.Token token = rxDocuments.issue(tenantId, h, queueEntryId);
        String qr = token.rxToken();
        String svg;
        try {
            svg = Qr.svg(Qr.encode(rxDocuments.verifyUrl(qr)));
        } catch (IllegalArgumentException e) {
            qr = svg = null; // the encoder takes 362 bytes; only an absurdly long registration number or base URL prints without a QR
        }
        return new PrescriptionPrintHeaderResponse(h.prescriptionId(), h.signedAt(), h.clinicName(), h.clinicRegion(),
                h.clinicPhone(), prescriberId, who == null ? null : who.name(), reg, coding.genericFirst(), qr, svg,
                who == null ? null : who.council(), token.validUntil(), token.revokedAt(), token.revokeReason());
    }
}
