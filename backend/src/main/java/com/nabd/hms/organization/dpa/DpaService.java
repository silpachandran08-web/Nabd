package com.nabd.hms.organization.dpa;

import com.nabd.hms.auth.api.StepUpOtp;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.dpa.dto.DpaAcceptRequest;
import com.nabd.hms.organization.dpa.dto.DpaAcceptResponse;
import com.nabd.hms.organization.dpa.dto.DpaOtpChallengeResponse;
import com.nabd.hms.organization.dpa.dto.DpaStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** ADM-002: show the DPA, confirm it with an OTP sent to the owner's mobile, store the signed PDF, open the data gate. */
@Service
@Transactional
public class DpaService {

    static final String EVENT = "organization.dpa.accepted.v1";

    private final DpaRepository repo;
    private final DataGate gate;
    private final TenantContext tenantContext;
    private final ClinicClock clinicClock;
    private final ActorDirectory actors;
    private final StepUpOtp otp;
    private final FieldCipher cipher;
    private final AuditService audit;
    private final EventPublisher events;
    private final Clock clock;

    DpaService(DpaRepository repo, DataGate gate, TenantContext tenantContext, ClinicClock clinicClock, ActorDirectory actors,
               StepUpOtp otp, FieldCipher cipher, AuditService audit, EventPublisher events, Clock clock) {
        this.repo = repo;
        this.gate = gate;
        this.tenantContext = tenantContext;
        this.clinicClock = clinicClock;
        this.actors = actors;
        this.otp = otp;
        this.cipher = cipher;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public DpaStatusResponse status() {
        UUID tenantId = tenantContext.current();
        String market = market(tenantId);
        String html = DpaText.html(DpaText.lines(market, repo.clinicName(tenantId)));
        boolean live = gate.isLive();
        Optional<DpaRepository.Acceptance> latest = repo.latest();
        DpaStatusResponse.Accepted accepted = latest.map(a -> new DpaStatusResponse.Accepted(a.version(), a.acceptedAt(),
                actors.findActor(tenantId, a.signerId()).map(ActorDirectory.Actor::name).orElse(null), a.method(), a.id().toString())).orElse(null);
        Instant due = latest.isPresent() && !DpaText.version().equals(latest.get().version())
                ? LocalDate.parse(DpaText.publishedOn()).plusDays(DpaText.reacceptDays()).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        return new DpaStatusResponse(DpaText.version(), market, html, accepted, live ? "live" : "demo_only", blockers(tenantId, live, latest.isPresent()), due);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public DpaOtpChallengeResponse requestOtp(UUID staffId) {
        requireOwner(tenantContext.current(), staffId);
        StepUpOtp.Challenge c = otp.issue(staffId, "dpa");
        return new DpaOtpChallengeResponse(c.id(), c.expiresAt());
    }

    /** noRollbackFor: a wrong code must keep its attempt count (5 per challenge). */
    @Transactional(noRollbackFor = ApiException.class)
    public DpaAcceptResponse accept(UUID staffId, String ip, DpaAcceptRequest req) {
        UUID tenantId = tenantContext.current();
        ActorDirectory.Actor signer = requireOwner(tenantId, staffId);
        if (!DpaText.version().equals(req.version())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_SUPERSEDED", "DPA version superseded", "A newer DPA version is current; reload and read it first.");
        }
        if (req.eSignRef() != null && !req.eSignRef().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ESIGN_NOT_SUPPORTED", "eSign not available", "Only OTP confirmation is available today.");
        }
        Optional<DpaRepository.Acceptance> existing = repo.find(req.version());
        if (existing.isPresent()) { // replay of an accepted version: same answer, nothing written
            return response(tenantId, existing.get().id());
        }
        if (!otp.verify(staffId, req.otpChallengeId(), "dpa", req.otp())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "Code not valid", "The code is wrong or expired. Request a new one.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        List<String> lines = new ArrayList<>(DpaText.lines(market(tenantId), repo.clinicName(tenantId)));
        lines.add("## Acceptance");
        lines.add("Version " + req.version() + ", accepted by " + signer.name() + " (" + signer.role() + ") on " + now + " by one-time code sent to the registered mobile.");
        byte[] pdf = SimplePdf.render(lines);
        String sha256 = sha256(pdf);
        byte[] enc = cipher.encrypt(pdf, FieldCipher.Aad.of(tenantId, "dpa_acceptances", "signed_pdf", id));
        if (!repo.insert(id, tenantId, req.version(), staffId, "otp", "db:" + id, enc, sha256, ip)) {
            return response(tenantId, repo.find(req.version()).orElseThrow().id()); // lost a race with the same request
        }
        audit.record(tenantId, "staff", staffId, signer.name(), signer.role(), ip, "setup.dpa_accepted", "dpa_acceptance", id, null,
                Map.of("version", req.version(), "method", "otp", "pdfSha256", sha256));
        gate.evict(tenantId);
        DpaAcceptResponse out = response(tenantId, id);
        events.publish(tenantId, EVENT, Map.of("tenantId", tenantId.toString(), "dpaVersion", req.version(), "acceptedAt", now.toString(), "dataGate", out.dataGate()));
        return out;
    }

    /** The decrypted signed PDF of an acceptance of this tenant (RLS hides other tenants' rows). */
    @Transactional
    public byte[] pdf(UUID acceptanceId) {
        UUID tenantId = tenantContext.current();
        byte[] enc = repo.pdf(acceptanceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No signed PDF."));
        return cipher.decrypt(enc, FieldCipher.Aad.of(tenantId, "dpa_acceptances", "signed_pdf", acceptanceId));
    }

    private DpaAcceptResponse response(UUID tenantId, UUID acceptanceId) {
        boolean live = gate.isLive();
        return new DpaAcceptResponse(acceptanceId, live ? "live" : "demo_only", "/v1/setup/dpa/acceptances/" + acceptanceId + "/pdf",
                blockers(tenantId, live, true));
    }

    private List<String> blockers(UUID tenantId, boolean live, boolean signed) {
        if (live) {
            return List.of();
        }
        List<String> b = new ArrayList<>();
        if (!signed) {
            b.add("DPA_REQUIRED");
        }
        if (!repo.hasSubscription(tenantId)) {
            b.add("PLAN_REQUIRED");
        }
        return b;
    }

    private ActorDirectory.Actor requireOwner(UUID tenantId, UUID staffId) {
        // the clinic's one built-in role is named Owner (custom roles are never built-in)
        return actors.findActor(tenantId, staffId).filter(a -> "Owner".equals(a.role()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Owner only", "Only the clinic owner can sign the data processing agreement."));
    }

    private String market(UUID tenantId) {
        String r = clinicClock.region(tenantId);
        return r == null ? "IN" : r;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
