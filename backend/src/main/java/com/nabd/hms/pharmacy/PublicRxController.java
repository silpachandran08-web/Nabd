package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.pharmacy.dto.RxVerificationDtos.PublicVerification;
import com.nabd.hms.shared.rxsign.RxQr;
import com.nabd.hms.shared.rxsign.RxSigningKeys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLN-023: the Rx QR is a link any phone can open (patient-web /rx/&lt;token&gt;), so verification is public: no sign-in, 30 calls a
 * minute per IP. The token is the PHL-013 signed payload; only a token signed by one of our keys is looked up, in the clinic it names
 * (the signature makes that id trustworthy). Unknown, altered or malformed tokens all get the same 404. The answer is the doctor,
 * validity and counts, never the patient, the medicines or a diagnosis. The keys are the same public JWKS the pharmacy caches, so a
 * verifier with no network can check the signature itself.
 */
@RestController
@RequestMapping("/v1/public/rx")
class PublicRxController {

    static final int PER_MINUTE = 30;
    private static final int MAX_TOKEN = 1000;

    private final RxVerificationService service;
    private final RxSigningKeys keys;
    private final TenantScope tenantScope;
    private final Clock clock;
    // shortcut: per-replica fixed one-minute window (30 x replicas in all); move to TokenBuckets when the shared limiter gets per-route buckets.
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    PublicRxController(RxVerificationService service, RxSigningKeys keys, TenantScope tenantScope, Clock clock) {
        this.service = service;
        this.keys = keys;
        this.tenantScope = tenantScope;
        this.clock = clock;
    }

    @GetMapping("/verify/{token}")
    ResponseEntity<PublicVerification> verify(@PathVariable String token, HttpServletRequest http) {
        limit(RequestMeta.clientIp(http));
        RxQr.Parsed p = token.length() > MAX_TOKEN ? null : RxQr.decode(token, keys).filter(RxQr.Parsed::signatureValid).orElse(null);
        if (p == null) throw unknown();
        PublicVerification v = tenantScope.run(p.claims().tenantId(), () -> service.verifyPublic(p.claims().prescriptionId())).orElseThrow(PublicRxController::unknown);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/keys")
    ResponseEntity<Map<String, Object>> keys() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(service.publicKeys());
    }

    private void limit(String ip) {
        long minute = clock.millis() / 60_000;
        if (windows.size() > 10_000) windows.values().removeIf(w -> w[0] != minute);
        long[] w = windows.compute(String.valueOf(ip), (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
        if (w[1] > PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests", "Too many checks from this network. Try again in a minute.");
        }
    }

    /** Tests only: start every IP's window afresh. */
    void resetLimits() {
        windows.clear();
    }

    private static ApiException unknown() {
        return new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_TOKEN", "Unknown prescription", "This prescription QR could not be verified.");
    }
}
