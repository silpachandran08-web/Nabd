package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.integration.AbhaGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * IDC-017: ABHA verify and create at the desk. Optional and never mandatory: a clinic with ABDM off, or a patient who declines,
 * causes no ABDM call (DEC-33). No @Transactional here: the ABDM calls must not run inside a database transaction.
 * The Aadhaar number is a method argument for one gateway call and nothing else: not stored, not audited, not logged.
 */
@Service
class AbhaService {

    private static final Pattern NUMBER = Pattern.compile("^\\d{2}-?\\d{4}-?\\d{4}-?\\d{4}$");
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._]{3,40}@[A-Za-z]{3,10}$");
    private static final Pattern AADHAAR = Pattern.compile("^\\d{12}$");
    private static final Pattern MOBILE = Pattern.compile("^\\+?\\d{10,15}$");

    record Result(String txnId, Boolean verified, String abhaLast4, String abhaAddress) {
    }

    private final AbdmSettingsService settings;
    private final AbhaGateway gateway;
    private final AbhaIdentifiers ids;

    AbhaService(AbdmSettingsService settings, AbhaGateway gateway, AbhaIdentifiers ids) {
        this.settings = settings;
        this.gateway = gateway;
        this.ids = ids;
    }

    Result verify(UUID staffId, String ip, UUID patientId, String step, String abhaNumberOrAddress, String otp, String txnId) {
        requireEnabled();
        ids.requirePatient(patientId);
        if ("init".equals(step)) {
            String v = abhaNumberOrAddress == null ? "" : abhaNumberOrAddress.strip();
            boolean number = NUMBER.matcher(v).matches();
            if (!number && !ADDRESS.matcher(v).matches()) {
                throw invalid("abha-invalid", "Enter the 14-digit ABHA number or the ABHA address.");
            }
            ids.requireNotLinkedElsewhere(number ? AbhaIdentifiers.NUMBER : AbhaIdentifiers.ADDRESS, v, patientId); // before an OTP is spent
            return new Result(gateway.loginOtp(v), null, null, null);
        }
        AbhaGateway.Profile p = gateway.loginConfirm(requireTxn(txnId), requireOtp(otp));
        return store(staffId, ip, patientId, p, "abdm_mobile_otp");
    }

    /** patientId optional: without it the new ABHA is created but linked to nobody yet (the desk links it with verify). */
    Result enrol(UUID staffId, String ip, UUID patientId, String step, String mode, String aadhaarNumber, String mobile, String otp, String txnId) {
        requireEnabled();
        if (patientId != null) {
            ids.requirePatient(patientId);
        }
        AbhaGateway.EnrolMode m = "aadhaar".equals(mode) ? AbhaGateway.EnrolMode.AADHAAR : "mobile".equals(mode) ? AbhaGateway.EnrolMode.MOBILE : null;
        if (m == null) {
            throw invalid("validation-failed", "mode must be aadhaar or mobile.");
        }
        if ("init".equals(step)) {
            String id = m == AbhaGateway.EnrolMode.AADHAAR ? aadhaarNumber : mobile;
            if (id == null || !(m == AbhaGateway.EnrolMode.AADHAAR ? AADHAAR : MOBILE).matcher(id.replaceAll("[\\s-]", "")).matches()) {
                throw invalid("validation-failed", m == AbhaGateway.EnrolMode.AADHAAR ? "A 12-digit Aadhaar number is required." : "A mobile number is required.");
            }
            return new Result(gateway.enrolOtp(m, id.replaceAll("[\\s-]", "")), null, null, null);
        }
        AbhaGateway.Profile p = gateway.enrolConfirm(m, requireTxn(txnId), requireOtp(otp), mobile);
        if (patientId == null) {
            return new Result(null, true, last4(p.abhaNumber()), p.abhaAddress());
        }
        return store(staffId, ip, patientId, p, "abdm_enrol");
    }

    private Result store(UUID staffId, String ip, UUID patientId, AbhaGateway.Profile p, String via) {
        String last4 = ids.link(patientId, AbhaIdentifiers.NUMBER, p.abhaNumber(), via);
        if (p.abhaAddress() != null && !p.abhaAddress().isBlank()) {
            ids.link(patientId, AbhaIdentifiers.ADDRESS, p.abhaAddress(), via);
        }
        ids.auditVerified(staffId, ip, patientId, AbhaIdentifiers.NUMBER, last4, via);
        return new Result(null, true, last4, p.abhaAddress());
    }

    boolean enabled() {
        return settings.find().map(AbdmSettingsService.Settings::enabled).orElse(false);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "abdm-disabled", "ABDM is off", "ABDM is not enabled for this clinic.");
        }
    }

    private static String last4(String number) {
        String d = AbhaIdentifiers.normalise(AbhaIdentifiers.NUMBER, number);
        return d.substring(Math.max(0, d.length() - 4));
    }

    private static String requireTxn(String txnId) {
        if (txnId == null || txnId.isBlank()) {
            throw invalid("validation-failed", "txnId from the init step is required.");
        }
        return txnId;
    }

    private static String requireOtp(String otp) {
        if (otp == null || !otp.matches("^\\d{4,8}$")) {
            throw invalid("abha-invalid", "Enter the OTP the patient received.");
        }
        return otp;
    }

    private static ApiException invalid(String slug, String detail) {
        return new ApiException(slug.equals("validation-failed") ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_CONTENT, slug, "Invalid request", detail);
    }
}
