package com.nabd.hms.identity.integration;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * IDC-017: ABHA create / verify over the ABDM v3 M1 APIs. Aadhaar numbers, mobiles, ABHA ids and OTPs are RSA-OAEP encrypted with
 * ABDM's public key before they leave the process. shortcut: request and response shapes follow the published v3 contract and a
 * local fixture, not a recording of the real sandbox (Needs external action, DEP-05); paths are blank config until then.
 */
@Component
class HttpAbhaGateway implements AbhaGateway {

    private static final String CONSENT_CODE = "abha-enrollment";
    private static final String CONSENT_VERSION = "1.4";

    private final HttpAbdmGateway base;
    private final AbhaProperties paths;
    private volatile PublicKey key;

    HttpAbhaGateway(HttpAbdmGateway base, AbhaProperties paths) {
        this.base = base;
        this.paths = paths;
    }

    @Override
    public String loginOtp(String abhaNumberOrAddress) {
        boolean address = abhaNumberOrAddress.contains("@");
        Map<String, Object> r = base.call(paths.loginOtpPath(), Map.of("scope", List.of("abha-login", "mobile-verify"),
                "loginHint", address ? "abha-address" : "abha-number", "loginId", encrypt(abhaNumberOrAddress), "otpSystem", "abdm"), true);
        return txn(r);
    }

    @Override
    public Profile loginConfirm(String txnId, String otp) {
        return profile(base.call(paths.loginVerifyPath(), Map.of("scope", List.of("abha-login", "mobile-verify"),
                "authData", Map.of("authMethods", List.of("otp"), "otp", Map.of("txnId", txnId, "otpValue", encrypt(otp)))), true));
    }

    @Override
    public String enrolOtp(EnrolMode mode, String identifier) {
        boolean aadhaar = mode == EnrolMode.AADHAAR;
        return txn(base.call(paths.enrolOtpPath(), Map.of("scope", List.of("abha-enrol"), "loginHint", aadhaar ? "aadhaar" : "mobile",
                "loginId", encrypt(identifier), "otpSystem", aadhaar ? "aadhaar" : "abdm"), true));
    }

    @Override
    public Profile enrolConfirm(EnrolMode mode, String txnId, String otp, String mobile) {
        Map<String, Object> otpBlock = new java.util.LinkedHashMap<>(Map.of("txnId", txnId, "otpValue", encrypt(otp)));
        if (mobile != null && !mobile.isBlank()) {
            otpBlock.put("mobile", mobile);
        }
        return profile(base.call(paths.enrolPath(), Map.of("authData", Map.of("authMethods", List.of("otp"), "otp", otpBlock),
                "consent", Map.of("code", CONSENT_CODE, "version", CONSENT_VERSION)), true));
    }

    private static String txn(Map<String, Object> r) {
        Object t = r.get("txnId");
        if (!(t instanceof String s) || s.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "abdm-unavailable", "ABDM unavailable", "The ABDM service cannot be reached right now; try again later.");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Profile profile(Map<String, Object> r) {
        Object p = r.get("ABHAProfile");
        Map<String, Object> m = p instanceof Map<?, ?> pm ? (Map<String, Object>) pm : r;
        String number = str(m.get("ABHANumber"));
        if (number.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "abha-invalid", "ABHA refused", "ABDM did not accept this ABHA id or code.");
        }
        String name = String.join(" ", List.of(str(m.get("firstName")), str(m.get("middleName")), str(m.get("lastName")))).replaceAll("\\s+", " ").strip();
        String dob = str(m.get("dob"));
        if (dob.isBlank() && !str(m.get("yearOfBirth")).isBlank()) {
            dob = String.format("%s-%02d-%02d", str(m.get("yearOfBirth")), num(m.get("monthOfBirth")), num(m.get("dayOfBirth")));
        }
        String address = str(m.get("preferredAbhaAddress"));
        return new Profile(number, address.isBlank() ? null : address, name.isBlank() ? str(m.get("name")) : name, dob, str(m.get("gender")), str(m.get("mobile")));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int num(Object o) {
        try {
            return Integer.parseInt(str(o));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            c.init(Cipher.ENCRYPT_MODE, publicKey());
            return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "abdm-unavailable", "ABDM unavailable", "The ABDM service cannot be reached right now; try again later.");
        }
    }

    private PublicKey publicKey() throws GeneralSecurityException {
        PublicKey k = key;
        if (k == null) {
            String pem = str(base.call(paths.certPath(), null, false).get("publicKey")).replaceAll("-----[A-Z ]+-----|\\s", "");
            if (pem.isBlank()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "abdm-unavailable", "ABDM unavailable", "The ABDM service cannot be reached right now; try again later.");
            }
            k = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            key = k;
        }
        return k;
    }
}
