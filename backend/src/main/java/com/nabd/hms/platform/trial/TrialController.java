package com.nabd.hms.platform.trial;

import jakarta.validation.Valid;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADM-003: anonymous trial sign-up (public chain, PublicEndpointsSecurityConfig). Scoped by the unguessable signup id. */
@RestController
@RequestMapping("/v1/public/trials")
class TrialController {

    record StartRequest(String clinicName, String specialty, String mobile, String market, boolean consentToContact) {
    }

    record VerifyRequest(String otp) {
    }

    private final TrialService service;

    TrialController(TrialService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> start(@Valid @RequestBody StartRequest req, HttpServletRequest http) {
        var s = service.start(req.clinicName(), req.specialty(), req.mobile(), req.market(), req.consentToContact(), RequestMeta.clientIp(http));
        return Map.of("signupId", s.signupId(), "otpChannel", s.otpChannel());
    }

    @PostMapping("/{signupId}/verify")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> verify(@PathVariable UUID signupId, @Valid @RequestBody VerifyRequest req) {
        var v = service.verify(signupId, req.otp());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signupId", v.signupId());
        out.put("status", v.status());
        out.put("jobId", v.jobId());
        out.put("pollUrl", "/v1/public/trials/" + v.signupId() + "/status");
        return out;
    }

    @GetMapping("/{signupId}/status")
    Map<String, Object> status(@PathVariable UUID signupId) {
        return Map.of("status", service.status(signupId));
    }

    @ExceptionHandler(TrialRateLimitException.class)
    ResponseEntity<ProblemDetail> rateLimited(TrialRateLimitException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        p.setType(URI.create("https://api.nabd.health/errors/rate_limited"));
        p.setTitle("Too many requests");
        p.setDetail(e.getMessage());
        p.setProperty("retryAfterSeconds", e.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
