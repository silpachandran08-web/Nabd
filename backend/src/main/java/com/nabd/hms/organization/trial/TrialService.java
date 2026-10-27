package com.nabd.hms.organization.trial;

import com.nabd.hms.auth.api.StepUpOtp;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TrialSubscriptions;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.dpa.dto.DpaOtpChallengeResponse;
import com.nabd.hms.organization.trial.dto.TrialConvertRequest;
import com.nabd.hms.organization.trial.dto.TrialConvertResponse;
import com.nabd.hms.organization.trial.dto.TrialStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-004: trial status for the banner and the owner's "Start for real". The purge itself runs in {@link TrialConversionWorker},
 * never in the request. The step-up code reuses the 'dpa' OTP purpose (the owner's registered mobile, 5 wrong tries per code):
 * a new purpose would rewrite the shared otp_requests CHECK another story may also be changing.
 */
@Service
public class TrialService {

    private final TrialRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clinicClock;
    private final ActorDirectory actors;
    private final StepUpOtp otp;
    private final AuditService audit;
    private final TrialSubscriptions subscriptions;

    TrialService(TrialRepository repo, TenantContext tenantContext, ClinicClock clinicClock, ActorDirectory actors, StepUpOtp otp, AuditService audit,
                 TrialSubscriptions subscriptions) {
        this.subscriptions = subscriptions;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clinicClock = clinicClock;
        this.actors = actors;
        this.otp = otp;
        this.audit = audit;
    }

    @Transactional
    public TrialStatusResponse status() {
        UUID tenantId = tenantContext.current();
        var sub = subscriptions.current();
        String status = sub.map(TrialSubscriptions.Current::status).orElse("none");
        var ends = sub.map(TrialSubscriptions.Current::endsAt).orElse(null);
        Integer daysLeft = null;
        if ("trialing".equals(status) && ends != null) {
            var zone = clinicClock.zone(tenantId);
            daysLeft = (int) Math.max(0, ChronoUnit.DAYS.between(clinicClock.today(zone), ends.atZone(zone).toLocalDate()));
        }
        List<String> blockers = new ArrayList<>();
        if (!repo.hasDpa()) {
            blockers.add("dpa");
        }
        if (sub.isEmpty() || "trialing".equals(status)) {
            blockers.add("plan");
        }
        var job = repo.latestJob().map(j -> new TrialStatusResponse.TrialConversion(j.id(), j.status(), j.rowsDeleted())).orElse(null);
        return new TrialStatusResponse(status, "trialing".equals(status) ? ends : null, daysLeft, blockers, job);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public DpaOtpChallengeResponse requestOtp(UUID staffId) {
        requireOwner(staffId);
        StepUpOtp.Challenge c = otp.issue(staffId, "dpa");
        return new DpaOtpChallengeResponse(c.id(), c.expiresAt());
    }

    /** noRollbackFor: a wrong code must keep its attempt count. */
    @Transactional(noRollbackFor = ApiException.class)
    public TrialConvertResponse convert(UUID staffId, String ip, TrialConvertRequest req) {
        UUID tenantId = tenantContext.current();
        ActorDirectory.Actor owner = requireOwner(staffId);
        if (!Boolean.TRUE.equals(req.confirm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED", "Confirmation needed", "Confirm that the demo data may be deleted.");
        }
        var open = repo.openJob();
        if (open.isPresent()) { // a repeat of the same request while it runs: same job
            return new TrialConvertResponse(open.get().id());
        }
        if (repo.converted() || !"trialing".equals(repo.tenantStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CONVERTED", "Already converted", "This clinic is no longer on a trial.");
        }
        if (!otp.verify(staffId, req.otpChallengeId(), "dpa", req.otp())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "Code not valid", "The code is wrong or expired. Request a new one.");
        }
        UUID jobId = repo.insertJob(staffId).orElseGet(() -> repo.openJob().orElseThrow().id());
        audit.record(tenantId, "staff", staffId, owner.name(), owner.role(), ip, "setup.trial_convert_requested", "trial_conversion", jobId, null, Map.of());
        return new TrialConvertResponse(jobId);
    }

    private ActorDirectory.Actor requireOwner(UUID staffId) {
        return actors.findActor(tenantContext.current(), staffId).filter(a -> "Owner".equals(a.role()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Owner only", "Only the clinic owner can start for real."));
    }
}
