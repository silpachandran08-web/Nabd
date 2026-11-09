package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.dto.RxVerificationDtos.Dispense;
import com.nabd.hms.pharmacy.dto.RxVerificationDtos.Line;
import com.nabd.hms.pharmacy.dto.RxVerificationDtos.Prescriber;
import com.nabd.hms.pharmacy.dto.RxVerificationDtos.Response;
import com.nabd.hms.shared.rxsign.RxQr;
import com.nabd.hms.shared.rxsign.RxSigningKeys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-013: a scanned Rx QR, checked in this order: readable, signed by one of our keys, from this clinic, a signed prescription here,
 * already fully dispensed, past its validity, else valid. Every readable scan is written to rx_verifications. An invalid answer
 * carries no prescription content at all (the pharmacist sees why, never what).
 */
@Service
@Transactional
public class RxVerificationService {

    private final RxVerificationRepository repo;
    private final PharmacyWorklistService worklist;
    private final RxSigningKeys keys;
    private final TenantContext tenant;
    private final Clock clock;

    RxVerificationService(RxVerificationRepository repo, PharmacyWorklistService worklist, RxSigningKeys keys, TenantContext tenant, Clock clock) {
        this.repo = repo;
        this.worklist = worklist;
        this.keys = keys;
        this.tenant = tenant;
        this.clock = clock;
    }

    public Map<String, Object> publicKeys() {
        return keys.publicJwks();
    }

    public Response verify(String qrPayload, UUID staffId) {
        UUID t = tenant.current();
        RxQr.Parsed parsed = RxQr.decode(qrPayload, keys).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "qr-unreadable",
                "QR not readable", "This is not a Nabd prescription QR. Scan it again or check the printout."));
        RxQr.Claims c = parsed.claims();
        if (!parsed.signatureValid()) {
            return invalid(t, null, "signature", staffId);
        }
        if (!t.equals(c.tenantId())) {
            return invalid(t, null, "other-tenant", staffId); // PHL-034 (network verification) is out of scope: never reveal content across clinics
        }
        RxVerificationRepository.Rx rx = repo.find(t, c.prescriptionId()).filter(r -> "signed".equals(r.status())).orElse(null);
        if (rx == null) {
            return invalid(t, c.prescriptionId(), "not-found", staffId);
        }
        List<Line> lines = repo.lines(t, rx.id()).stream().map(l -> new Line(l.prescriptionItemId(), l.drugName(), l.quantity(), l.dispensed(),
                RxGate.remaining(l.quantity(), l.dispensed()))).toList();
        List<Line> countable = lines.stream().filter(l -> l.qtyPrescribed() != null).toList();
        boolean fullyDispensed = !countable.isEmpty() && countable.stream().allMatch(l -> l.remaining().signum() == 0);
        Instant validUntil = worklist.validUntil(rx.id(), rx.queueEntryId());
        UUID entryId = repo.worklistEntry(t, rx.id()).orElse(null);
        Prescriber prescriber = new Prescriber(rx.signerName(), rx.signerRegistrationNo());
        String status = fullyDispensed ? "dispensed" : validUntil != null && !clock.instant().isBefore(validUntil) ? "expired" : "valid";
        Dispense dispense = fullyDispensed ? repo.lastDispense(t, rx.id()).map(d -> new Dispense(d.at(), d.clinicName() + " · " + d.invoiceNumber())).orElse(null) : null;
        repo.record(t, rx.id(), status, null, staffId);
        return new Response(status, null, rx.id(), rx.queueEntryId(), entryId, prescriber, rx.signedAt(), validUntil, dispense,
                lines.stream().filter(l -> l.remaining().signum() > 0 || l.qtyPrescribed() == null).toList());
    }

    private Response invalid(UUID t, UUID prescriptionId, String reason, UUID staffId) {
        repo.record(t, prescriptionId, "invalid", reason, staffId);
        return new Response("invalid", reason, null, null, null, null, null, null, null, List.of());
    }
}
