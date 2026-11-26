package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.RequestCorrelationFilter;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.DataGatePort;
import com.nabd.hms.patient.BrandDirectoryRepository.Candidate;
import com.nabd.hms.patient.BrandDirectoryRepository.LinkOutcome;
import com.nabd.hms.patient.BrandDirectoryRepository.State;
import com.nabd.hms.patient.api.PatientRefs;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-024: a patient registered at one branch is recognised at a sibling branch through an explicit, logged link. The limits
 * (own brand and region only, brand profile DEMOGRAPHICS, name / sex / year of birth before a link, never an opted-out
 * patient, both clinics logged) are enforced by the V3690 functions; this class adds the staff gates, the per-user limit and
 * the caller's own rows. No clinical row is ever copied or linked: branch A's history still needs a consent artefact.
 */
@Service
public class BrandDirectoryService {

    /** Lookups and refused links per staff member per minute (spec: 30/min/user). Counted in Postgres, so it holds without Valkey. */
    static final int ATTEMPTS_PER_MINUTE = 30;

    public record Match(UUID sourceTenantId, String branchLabel, String sourcePatientRef, String displayName, String sex, Integer birthYear) {
    }

    /** checkInPerson: the mobile is known at a sibling branch but the date of birth given does not match; nothing about that record is shown. */
    public record Lookup(List<Match> matches, boolean checkInPerson) {
    }

    /** outcome: ok, disabled, not_found, opted_out, dob_mismatch or already_linked; patientId only for ok. */
    public record LinkResult(String outcome, UUID patientId) {
    }

    public record Link(UUID id, String branchLabel, Instant linkedAt) {
    }

    public record PatientLinks(boolean optedOut, List<Link> links) {
    }

    public record Settings(String profile, boolean canEdit, int siblingBranches) {
    }

    /** direction: outgoing = searched from this clinic, incoming = this clinic's patient looked up by a sibling branch. */
    public record LogItem(long id, Instant at, String direction, String kind, String branchLabel, String staffName, UUID patientId,
                          String patientName, String mrn) {
    }

    private final BrandDirectoryRepository repo;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final DataGatePort dataGate;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final PatientRefs refs;

    BrandDirectoryService(BrandDirectoryRepository repo, PatientRepository patients, PatientService patientService, DataGatePort dataGate,
                          ActorDirectory actors, AuditService audit, EventPublisher events, PatientRefs refs) {
        this.repo = repo;
        this.patients = patients;
        this.patientService = patientService;
        this.dataGate = dataGate;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.refs = refs;
    }

    @Transactional(readOnly = true)
    public boolean enabled(UUID staffId) {
        return repo.state(staffId).enabled();
    }

    /** Not read-only: every lookup is logged in both clinics. */
    @Transactional
    public Lookup lookup(UUID tenantId, UUID staffId, String phone, LocalDate dob, String ip) {
        ActorDirectory.Actor actor = gate(tenantId, staffId);
        String e164 = PhoneNumbers.require(phone, patients.region(tenantId), "phone");
        List<Candidate> found = repo.lookup(e164, dob, staffId, actor.name(), actor.role(), ip, requestId());
        return new Lookup(found.stream().filter(c -> !c.dobMismatch())
                .map(c -> new Match(c.sourceTenantId(), c.branchLabel(), c.sourcePatientRef(), c.displayName(), c.sex(), c.birthYear())).toList(),
                found.stream().anyMatch(Candidate::dobMismatch));
    }

    /**
     * Links (and, when this clinic has no such patient yet, imports the demographics of) a sibling branch's patient. A refusal
     * returns its outcome instead of throwing, so the audit row that feeds the per-user limit commits: a date of birth cannot be
     * guessed by repeated attempts.
     */
    @Transactional
    public LinkResult link(UUID tenantId, UUID staffId, UUID sourceTenantId, String sourcePatientRef, String phone, LocalDate dob, String ip) {
        ActorDirectory.Actor actor = gate(tenantId, staffId);
        String e164 = PhoneNumbers.require(phone, patients.region(tenantId), "phone");
        LinkOutcome o;
        try {
            o = repo.linkCreate(sourceTenantId, sourcePatientRef, e164, dob, UUID.randomUUID(), staffId, ip, requestId());
            if ("ok".equals(o.outcome())) {
                if (o.isNew()) {
                    patients.insert(o.localPatientId(), tenantId, o.name(), o.phone(), o.dob(), o.gender(), null, null, null, false);
                }
                repo.insertLocalLink(tenantId, o.localPatientId(), sourceTenantId, sourcePatientRef, staffId);
            }
        } catch (DuplicateKeyException e) { // two desks linking the same patient at once
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_LINKED", "ALREADY_LINKED", "This patient is already linked to that branch.",
                    Map.of("code", "ALREADY_LINKED"));
        }
        if (!"ok".equals(o.outcome())) {
            audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "patient.brand_link.refused", "brand_directory", null, null,
                    Map.of("reason", o.outcome(), "sourceTenantId", sourceTenantId));
            return new LinkResult(o.outcome(), null);
        }
        Map<String, Object> after = new LinkedHashMap<>(); // ids only: no name, phone or date of birth
        after.put("linkedTenantId", sourceTenantId);
        after.put("basis", "desk_phone_dob");
        after.put("demographicsCopied", o.isNew());
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "patient.branch_link.created", "patient", o.localPatientId(), null, after);
        events.publish(tenantId, DomainEventType.PATIENT_BRANCH_LINK_CREATED, o.localPatientId(),
                Map.of("patientId", o.localPatientId(), "linkedTenantId", sourceTenantId));
        return new LinkResult("ok", o.localPatientId());
    }

    @Transactional(readOnly = true)
    public PatientLinks links(UUID tenantId, UUID staffId, UUID patientId) {
        visible(tenantId, staffId, patientId);
        return new PatientLinks(repo.optedOut(tenantId, patientId),
                repo.links(tenantId, patientId).stream().map(l -> new Link(l.id(), l.branchLabel(), l.linkedAt())).toList());
    }

    /** The owner ends a link: both rows go, both patient records stand alone, both clinics are audited. Built-in Owner role only. */
    @Transactional
    public void unlink(UUID tenantId, UUID staffId, UUID patientId, UUID linkId, String ip) {
        visible(tenantId, staffId, patientId);
        if (actors.activeOwners(tenantId).stream().noneMatch(o -> o.id().equals(staffId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "OWNER_ONLY", "Only the owner can remove a link to another branch.",
                    Map.of("code", "OWNER_ONLY"));
        }
        List<UUID> ended = repo.linkEnd(patientId, linkId, "owner", ip);
        if (ended.isEmpty()) {
            throw ApiException.notFound();
        }
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        ended.forEach(other -> audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "patient.branch_link.ended", "patient",
                patientId, Map.of("linkedTenantId", other), Map.of("endedBy", "owner")));
    }

    /** The patient asked in chat (Edge, verified phone) to end every link of their record. Call inside TenantScope.run. Returns how many ended. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int unlinkForPatient(UUID tenantId, String patientRef, String serviceId) {
        UUID patientId = refs.patientIdFor(patientRef).orElseThrow(ApiException::notFound);
        List<UUID> ended = repo.linkEnd(patientId, null, "patient", null);
        ended.forEach(other -> audit.record(tenantId, "system", null, "patient via edge", serviceId, null, "patient.branch_link.ended", "patient",
                patientId, Map.of("linkedTenantId", other), Map.of("endedBy", "patient")));
        return ended.size();
    }

    /** The patient's own choice, recorded at the desk (also settable at registration): never listed for a sibling branch again. */
    @Transactional
    public void setOptOut(UUID tenantId, UUID staffId, UUID patientId, boolean optOut) {
        visible(tenantId, staffId, patientId);
        if (repo.optedOut(tenantId, patientId) == optOut) {
            return;
        }
        patients.update(tenantId, patientId, Map.of("brand_directory_opt_out", optOut));
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "patient.brand_directory.opt_out_changed", "patient", patientId,
                Map.of("optOut", !optOut), Map.of("optOut", optOut));
    }

    @Transactional(readOnly = true)
    public Settings settings(UUID staffId) {
        State s = repo.state(staffId);
        return new Settings(s.profile(), s.brandOwner(), s.siblingCount());
    }

    /**
     * Only the brand's owner account on the built-in Owner role may switch the profile (checked in the database function, which
     * also audits the change in every clinic of the brand). Switching sharing on needs the owner's statement that the published
     * privacy notice discloses the directory: the notice is free text, so this cannot be verified by the system.
     */
    @Transactional
    public Settings setProfile(UUID staffId, String profile, boolean noticeDisclosesDirectory, String ip) {
        if ("DEMOGRAPHICS".equals(profile) && !noticeDisclosesDirectory) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOTICE_DISCLOSURE_REQUIRED", "NOTICE_DISCLOSURE_REQUIRED",
                    "Confirm that your privacy notice tells patients their details can be found at your other branches.",
                    Map.of("code", "NOTICE_DISCLOSURE_REQUIRED"));
        }
        if (!repo.setProfile(profile, staffId, ip)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "OWNER_ONLY", "Only the brand's owner can change sharing between branches.",
                    Map.of("code", "OWNER_ONLY"));
        }
        return settings(staffId);
    }

    @Transactional(readOnly = true)
    public List<LogItem> lookups(UUID tenantId, UUID staffId) {
        patientService.requireVerifiedAndResolveScope(tenantId, staffId);
        List<BrandDirectoryRepository.LogRow> rows = repo.lookups(tenantId, 100);
        Map<UUID, ActorDirectory.Actor> staff = actors.findActors(tenantId,
                rows.stream().filter(r -> "staff".equals(r.actorType()) && r.actorId() != null).map(BrandDirectoryRepository.LogRow::actorId).distinct().toList());
        return rows.stream().map(r -> {
            boolean outgoing = "staff".equals(r.actorType());
            ActorDirectory.Actor a = outgoing ? staff.get(r.actorId()) : null;
            return new LogItem(r.id(), r.at(), outgoing ? "outgoing" : "incoming", "brand_link_import".equals(r.kind()) ? "link" : "lookup",
                    r.branchLabel(), a == null ? null : a.name(), r.patientId(), r.patientName(), r.mrn());
        }).toList();
    }

    /** Staff verification (NB-041), real-data gate (ADM-002), brand profile and the per-user limit, in that order; nothing is written before it passes. */
    private ActorDirectory.Actor gate(UUID tenantId, UUID staffId) {
        patientService.requireVerifiedAndResolveScope(tenantId, staffId);
        State s = repo.state(staffId);
        if (!s.enabled()) {
            throw disabled();
        }
        dataGate.requireRealData();
        if (s.recentAttempts() >= ATTEMPTS_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests",
                    "Too many branch lookups in a minute. Wait a minute and try again.", Map.of("retryAfterSeconds", 60));
        }
        return actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
    }

    static ApiException disabled() {
        return new ApiException(HttpStatus.FORBIDDEN, "DIRECTORY_DISABLED", "DIRECTORY_DISABLED",
                "Finding patients of other branches is switched off for this brand.", Map.of("code", "DIRECTORY_DISABLED"));
    }

    /** 404 (never 403) for a patient this caller may not see, like GET /v1/patients/{id}. */
    private void visible(UUID tenantId, UUID staffId, UUID patientId) {
        UUID scopedToDoctorId = patientService.requireVerifiedAndResolveScope(tenantId, staffId);
        patients.findVisibleById(tenantId, patientId, scopedToDoctorId).orElseThrow(ApiException::notFound);
    }

    private static String requestId() {
        return MDC.get(RequestCorrelationFilter.MDC_KEY);
    }
}
