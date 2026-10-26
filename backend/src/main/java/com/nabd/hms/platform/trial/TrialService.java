package com.nabd.hms.platform.trial;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.organization.api.RegionRules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-003 (part): public trial sign-up up to a verified mobile. Stores the mobile only as an HMAC, pins the region from the
 * phone's country, refuses a second trial per mobile for 90 days, limits sign-ups per IP, and checks the OTP with an attempt cap.
 * What is NOT here: the provisioning job and seed_demo step (waits on the self-serve provisioning columns), OTP delivery
 * (TrialOtpSender has no production bean) and the captcha check (no verifier in the tree).
 */
@Service
public class TrialService {

    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration IP_WINDOW = Duration.ofHours(1);
    static final int IP_LIMIT = 5;
    static final Duration TRIAL_PERIOD = Duration.ofDays(90);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_OTP_ATTEMPTS = 5;
    static final Set<String> SPECIALTIES = Set.of("dermatology", "dental", "general_practice", "paediatrics");

    record Started(UUID signupId, String otpChannel) {
    }

    record Verified(UUID signupId, UUID jobId, String status) {
    }

    private final TrialRepository repo;
    private final RegionRules regions;
    private final ObjectProvider<TrialOtpSender> sender;
    private final Clock clock;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();

    TrialService(TrialRepository repo, RegionRules regions, ObjectProvider<TrialOtpSender> sender, Clock clock,
                 @Value("${app.trial.hash-pepper:}") String pepper) {
        this.repo = repo;
        this.regions = regions;
        this.sender = sender;
        this.clock = clock;
        this.pepper = pepper;
    }

    @Transactional
    Started start(String clinicName, String specialty, String mobile, String market, boolean consentToContact, String clientIp) {
        TrialOtpSender otp = sender.getIfAvailable();
        if (pepper.isBlank() || otp == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "trial-signup-unavailable", "Sign-up unavailable", "Trial sign-up is not available right now.");
        }
        if (!consentToContact || clinicName == null || clinicName.isBlank() || clinicName.length() > 120 || !SPECIALTIES.contains(specialty)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "Give a clinic name, a specialty from the list, and agree to be contacted.");
        }
        Instant now = clock.instant();
        String ipHash = hmac("ip:" + clientIp);
        List<Instant> recent = repo.ipAttempts(ipHash, now.minus(IP_WINDOW));
        if (recent.size() >= IP_LIMIT) {
            throw new TrialRateLimitException(Math.max(1, Duration.between(now, recent.get(recent.size() - IP_LIMIT).plus(IP_WINDOW)).toSeconds() + 1));
        }
        String e164 = PhoneNumbers.e164OrNull(mobile, market == null || market.isBlank() ? null : market.trim().toUpperCase(java.util.Locale.ROOT));
        if (e164 == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-mobile", "Invalid mobile", "Enter a valid mobile number.", Map.of("code", "INVALID_MOBILE"));
        }
        String region = regionOf(e164);
        if (market != null && !market.isBlank() && !market.trim().equalsIgnoreCase(region)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "market-mismatch", "Market does not match the mobile", "The mobile number belongs to a different market.",
                    Map.of("code", "MARKET_MISMATCH"));
        }
        String mobileHash = hmac("mobile:" + e164);
        if (repo.verifiedSince(mobileHash, now.minus(TRIAL_PERIOD))) {
            throw new ApiException(HttpStatus.CONFLICT, "trial-exists", "Trial already started", "This mobile already has a trial; sign in to it.", Map.of("code", "TRIAL_EXISTS"));
        }
        if (repo.otpSentSince(mobileHash, now.minus(RESEND_COOLDOWN))) {
            throw new TrialRateLimitException(RESEND_COOLDOWN.toSeconds());
        }
        UUID id = UUID.randomUUID();
        String code = String.format("%06d", random.nextInt(1_000_000));
        repo.insert(id, mobileHash, ipHash, region, region, clinicName.trim(), specialty, hmac("otp:" + id + ":" + code), now);
        otp.send(e164, code, "whatsapp");
        return new Started(id, "whatsapp");
    }

    /** noRollbackFor: a wrong code must still count against the attempt cap. */
    @Transactional(noRollbackFor = ApiException.class)
    Verified verify(UUID signupId, String code) {
        var s = repo.find(signupId).orElseThrow(TrialService::expired);
        Instant now = clock.instant();
        if ("verified".equals(s.status()) || "provisioning".equals(s.status()) || "ready".equals(s.status())) {
            return new Verified(s.id(), s.jobId(), s.status());
        }
        if (!"otp_sent".equals(s.status()) || now.isAfter(s.createdAt().plus(OTP_TTL)) || s.otpAttempts() >= MAX_OTP_ATTEMPTS) {
            repo.markExpired(s.id());
            throw expired();
        }
        boolean ok = code != null && MessageDigest.isEqual(hmac("otp:" + s.id() + ":" + code).getBytes(StandardCharsets.UTF_8), s.otpHash().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            repo.failedAttempt(s.id());
            throw new ApiException(HttpStatus.BAD_REQUEST, "otp-invalid", "Code not correct", "That code is not correct.", Map.of("code", "OTP_INVALID"));
        }
        repo.markVerified(s.id());
        return new Verified(s.id(), null, "verified");
    }

    @Transactional
    String status(UUID signupId) {
        return repo.find(signupId).orElseThrow(TrialService::expired).status();
    }

    /** The region pinned at sign-up: the ISO country of the number, which must be an active region of ours. */
    private String regionOf(String e164) {
        try {
            String iso = PhoneNumberUtil.getInstance().getRegionCodeForNumber(PhoneNumberUtil.getInstance().parse(e164, null));
            if (iso != null && regions.all().stream().anyMatch(r -> r.active() && r.code().equals(iso))) {
                return iso;
            }
        } catch (NumberParseException ignored) {
            // falls through to the same refusal
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "market-unsupported", "Market not supported", "Nabd is not available for this mobile number's country yet.",
                Map.of("code", "MARKET_UNSUPPORTED"));
    }

    static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "signup-expired", "Sign-up expired", "This sign-up has expired; start again.", Map.of("code", "SIGNUP_EXPIRED"));
    }

    String hmac(String v) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(v.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
