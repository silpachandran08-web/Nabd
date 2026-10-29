package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.setup.dto.ChargeHeadResponse;
import com.nabd.hms.organization.setup.dto.ChargeHeadWriteRequest;
import com.nabd.hms.organization.setup.dto.ClinicHolidayResponse;
import com.nabd.hms.organization.setup.dto.ClinicHolidayWriteRequest;
import com.nabd.hms.organization.setup.dto.ClinicProfileResponse;
import com.nabd.hms.organization.setup.dto.ClinicProfileWriteRequest;
import com.nabd.hms.organization.setup.dto.ConsentContactResponse;
import com.nabd.hms.organization.setup.dto.ConsentContactWriteRequest;
import com.nabd.hms.organization.setup.dto.ExportJobRequest;
import com.nabd.hms.organization.setup.dto.ExportJobResponse;
import com.nabd.hms.organization.setup.dto.ImportJobRequest;
import com.nabd.hms.organization.setup.dto.ImportJobResponse;
import com.nabd.hms.organization.setup.dto.ComplianceHealthResponse;
import com.nabd.hms.organization.setup.dto.LicenceResponse;
import com.nabd.hms.organization.setup.dto.LicenceWriteRequest;
import com.nabd.hms.organization.setup.dto.PayrollExportRowResponse;
import com.nabd.hms.organization.setup.dto.PolicyResponse;
import com.nabd.hms.organization.setup.dto.PolicyWriteRequest;
import com.nabd.hms.organization.setup.dto.SetupChecklistItemResponse;
import com.nabd.hms.organization.setup.dto.StaffShiftResponse;
import com.nabd.hms.organization.setup.dto.StaffShiftWriteRequest;
import com.nabd.hms.organization.setup.dto.StaffSummaryResponse;
import com.nabd.hms.organization.setup.dto.SubscriptionSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.organization.setup.SetupModels.ChargeHeadRow;
import static com.nabd.hms.organization.setup.SetupModels.HolidayRow;
import static com.nabd.hms.organization.setup.SetupModels.LicenceRow;
import static com.nabd.hms.organization.setup.SetupModels.PolicyRow;
import static com.nabd.hms.organization.setup.SetupModels.SetupProgressRow;
import static com.nabd.hms.organization.setup.SetupModels.StaffShiftRow;
import static com.nabd.hms.organization.setup.SetupModels.TenantProfileRow;

@Service
public class SetupService implements com.nabd.hms.organization.api.ClinicPolicies {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final SetupRepository repo;
    private final TenantContext tenantContext;

    private final Clock clock;
    private final com.nabd.hms.common.EventPublisher events;

    private final com.nabd.hms.common.TenantScope tenantScope;
    private final com.nabd.hms.organization.api.Entitlements entitlements;
    private final com.nabd.hms.iam.api.ActorDirectory actors;

    private final com.nabd.hms.patient.api.PrivacyNotices privacyNotices;
    private final CalendarService calendar;
    private final com.nabd.hms.organization.licence.LicenceLifecycleService licences;
    private final PriceChangeLog pricingLog;

    SetupService(SetupRepository repo, TenantContext tenantContext, Clock clock, com.nabd.hms.common.EventPublisher events,
                 com.nabd.hms.common.TenantScope tenantScope, com.nabd.hms.organization.api.Entitlements entitlements,
                 com.nabd.hms.iam.api.ActorDirectory actors, com.nabd.hms.patient.api.PrivacyNotices privacyNotices,
                 CalendarService calendar,
                 com.nabd.hms.organization.licence.LicenceLifecycleService licences, PriceChangeLog pricingLog) {
        this.calendar = calendar;
        this.licences = licences;
        this.pricingLog = pricingLog;
        this.actors = actors;
        this.entitlements = entitlements;
        this.privacyNotices = privacyNotices;
        this.tenantScope = tenantScope;
        this.events = events;
        this.clock = clock;
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<SetupChecklistItemResponse> getChecklist(UUID tenantId) {
        tenantContext.set(tenantId);
        List<SetupProgressRow> rows = repo.listProgress(tenantId);
        // ADM-007: the first pending step is the one the owner is on
        String current = rows.stream().filter(r -> "pending".equals(r.status())).map(SetupProgressRow::step).findFirst().orElse(null);
        return rows.stream().map(r -> toChecklistItem(r, r.step().equals(current))).toList();
    }

    /** status is 'skipped' or 'done', always derived from which endpoint the controller called —
     * never taken from a client-supplied body, so a request to .../skip can't actually mark done. */
    @Transactional
    public void updateProgress(UUID tenantId, String step, String status) {
        tenantContext.set(tenantId);
        validateStep(step);
        if (SetupSteps.DPA.equals(step)) { // ADM-007: never skipped; done only once a DPA is on file
            if ("skipped".equals(status)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "step-invalid", "STEP_INVALID", "The data processing agreement cannot be skipped.");
            }
            if (!repo.hasDpaAcceptance()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "step-invalid", "STEP_INVALID", "Accept the data processing agreement first.");
            }
        }
        repo.updateProgress(tenantId, step, status);
        if ("go_live".equals(step) && "done".equals(status)) {
            repo.markSetupCompleted(tenantId);
        }
        log.info("setup progress {} marked {} for tenant {}", step, status, tenantId);
    }

    @Transactional
    public ClinicProfileResponse getProfile(UUID tenantId) {
        tenantContext.set(tenantId);
        TenantProfileRow row = repo.findProfile(tenantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tenant-not-found", "Tenant not found", "Tenant not found"));
        return toProfileResponse(row);
    }

    @Transactional
    public ClinicProfileResponse updateProfile(UUID tenantId, UUID callerStaffId, ClinicProfileWriteRequest req) {
        tenantContext.set(tenantId);
        // Every schedule, queue day and report reads this (ClinicClock) — only real IANA zones get in.
        if (!ClinicClock.isValidZone(req.timezone())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-timezone", "Invalid timezone",
                    "Use a timezone like Asia/Kolkata, Asia/Riyadh or UTC.");
        }
        repo.updateProfile(tenantId, req);
        log.info("clinic profile updated for tenant {} by {}", tenantId, callerStaffId);
        configChanged(tenantId, "clinic.profile");
        return getProfile(tenantId);
    }

    @Transactional
    public List<ChargeHeadResponse> listCharges(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listCharges(tenantId).stream().map(this::toChargeResponse).toList();
    }

    @Transactional
    public ChargeHeadResponse addCharge(UUID tenantId, UUID callerStaffId, ChargeHeadWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertCharge(tenantId, req);
        log.info("charge head {} added for tenant {} by {}", id, tenantId, callerStaffId);
        catalogueChanged(tenantId, id, "added");
        return repo.findCharge(tenantId, id).map(this::toChargeResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "charge-not-found", "Charge not found after insert", "Charge not found after insert"));
    }

    @Transactional
    public ChargeHeadResponse updateCharge(UUID tenantId, UUID callerStaffId, UUID id, ChargeHeadWriteRequest req) {
        tenantContext.set(tenantId);
        ChargeHeadRow before = repo.findCharge(tenantId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "charge-not-found", "Charge not found", "Charge not found"));
        pricingLog.logChargeUpdate(id, before, req, callerStaffId); // FIN-016: same transaction as the update
        repo.updateCharge(tenantId, id, req);
        log.info("charge head {} updated for tenant {} by {}", id, tenantId, callerStaffId);
        catalogueChanged(tenantId, id, "updated");
        return repo.findCharge(tenantId, id).map(this::toChargeResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "charge-not-found", "Charge not found after update", "Charge not found after update"));
    }

    /** OPS-015: which setting changed, never its value (a value can be free text). */
    private void configChanged(UUID tenantId, String configKey) {
        events.publish(tenantId, com.nabd.hms.common.DomainEventType.ORGANIZATION_CONFIG_CHANGED, tenantId,
                java.util.Map.of("configKey", configKey, "status", "updated"));
    }

    private void catalogueChanged(UUID tenantId, UUID chargeHeadId, String status) {
        events.publish(tenantId, com.nabd.hms.common.DomainEventType.CATALOGUE_CHANGED, chargeHeadId,
                java.util.Map.of("chargeHeadId", chargeHeadId, "status", status));
    }

    @Transactional
    public List<PolicyResponse> listPolicies(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listPolicies(tenantId).stream().map(this::toPolicyResponse).toList();
    }

    @Transactional
    public PolicyResponse updatePolicy(UUID tenantId, UUID callerStaffId, String policyKey, PolicyWriteRequest req) {
        tenantContext.set(tenantId);
        if ("session.idle_lock_minutes".equals(policyKey) && !req.value().matches("^(?:[2-9]|[12][0-9]|30)$")) { // FEF-005
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "Idle lock must be a whole number of minutes from 2 to 30");
        }
        if ("follow_up_window_days".equals(policyKey) && !req.value().matches("^(?:[1-9]|[1-8][0-9]|90)$")) { // FIN-015
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "The follow-up window is a whole number of days from 1 to 90.");
        }
        if ("cash_variance_tolerance".equals(policyKey) && !req.value().matches("^\\d{1,9}$")) { // FIN-005: minor units
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "Cash tolerance must be a whole number of minor currency units (0 or more)");
        }
        if ("discount_threshold_amount".equals(policyKey) && !req.value().matches("^(off|\\d{1,12})$")) { // FIN-018: minor units
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "The discount threshold is a whole number of minor currency units, or off.");
        }
        if ("discount_threshold_percent".equals(policyKey) && !req.value().matches("^(off|100(\\.0{1,2})?|\\d{1,2}(\\.\\d{1,2})?)$")) { // FIN-018
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "The discount threshold is a percent from 0 to 100 (two decimals at most), or off.");
        }
        if (policyKey.startsWith("print.default_paper.") && !req.value().matches("^(a4|a5|80mm)$")) { // FEF-046
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "Default paper must be a4, a5 or 80mm");
        }
        repo.updatePolicy(tenantId, policyKey, req.value());
        log.info("policy {} updated for tenant {} by {}", policyKey, tenantId, callerStaffId);
        configChanged(tenantId, policyKey);
        return repo.listPolicies(tenantId).stream()
                .filter(p -> p.policyKey().equals(policyKey))
                .findFirst()
                .map(this::toPolicyResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "policy-not-found", "Policy not found", "Policy not found"));
    }

    /** FIN-005: one policy's stored value, without seeding defaults (safe inside a read-only transaction). */
    @Transactional(readOnly = true)
    @Override
    public java.util.Optional<String> policyValue(UUID tenantId, String policyKey) {
        return tenantScope.run(tenantId, () -> repo.findPolicyValue(tenantId, policyKey)); // kernel API: joins and binds or checks (PLT-019)
    }

    @Transactional
    public ConsentContactResponse getConsentContact(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.findConsentContact(tenantId)
                .map(r -> new ConsentContactResponse(r.name(), r.email(), r.phone()))
                .orElse(new ConsentContactResponse("", "", ""));
    }

    @Transactional
    public ConsentContactResponse updateConsentContact(UUID tenantId, UUID callerStaffId, ConsentContactWriteRequest req) {
        tenantContext.set(tenantId);
        repo.updateConsentContact(tenantId, req);
        privacyNotices.seedInitial(); // IDC-003: the first saved grievance contact gives the clinic its v1 notices
        log.info("consent contact updated for tenant {} by {}", tenantId, callerStaffId);
        return new ConsentContactResponse(req.name(), req.email(), req.phone());
    }

    @Transactional
    public List<ClinicHolidayResponse> listHolidays(UUID tenantId) {
        tenantContext.set(tenantId);
        return calendar.withBookingsToMove(repo.listHolidays(tenantId).stream().map(this::toHolidayResponse).toList());
    }

    @Transactional
    public ClinicHolidayResponse addHoliday(UUID tenantId, UUID callerStaffId, ClinicHolidayWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertHoliday(tenantId, req);
        log.info("holiday {} added for tenant {} by {}", id, tenantId, callerStaffId);
        calendar.changed(tenantId, List.of(new CalendarService.Change(req.holidayDate(), "closed", null)));
        return new ClinicHolidayResponse(id.toString(), req.holidayDate(), req.name(), req.recurring(), "closed", List.of(), null, null);
    }

    @Transactional
    public void deleteHoliday(UUID tenantId, UUID callerStaffId, UUID id) {
        tenantContext.set(tenantId);
        LocalDate date = calendar.dateOf(tenantId, id).orElse(null);
        int deleted = repo.deleteHoliday(tenantId, id);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "holiday-not-found", "Holiday not found", "Holiday not found");
        }
        calendar.changed(tenantId, List.of(new CalendarService.Change(date, "open", null))); // the day is an ordinary day again
        log.info("holiday {} deleted for tenant {} by {}", id, tenantId, callerStaffId);
    }

    @Transactional
    public List<StaffShiftResponse> listShifts(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listShifts(tenantId).stream().map(this::toShiftResponse).toList();
    }

    @Transactional
    public StaffShiftResponse addShift(UUID tenantId, UUID callerStaffId, StaffShiftWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertShift(tenantId, req);
        log.info("shift {} added for tenant {} by {}", id, tenantId, callerStaffId);
        return new StaffShiftResponse(id.toString(), req.staffId().toString(), null, req.patternJson(), req.effectiveFrom(), req.effectiveTo());
    }

    @Transactional
    public List<PayrollExportRowResponse> payrollExport(UUID tenantId, String monthParam) {
        tenantContext.set(tenantId);
        YearMonth month;
        try {
            month = monthParam == null ? YearMonth.now(clock) : YearMonth.parse(monthParam);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-month", "Invalid month", "Month must be in ISO-8601 format such as 2026-07");
        }
        return repo.payrollExport(tenantId, month).stream()
                .map(r -> new PayrollExportRowResponse(r.staffId().toString(), r.staffName(), r.roleName(),
                        r.daysPresent(), r.hours(), r.salary(), r.notes()))
                .toList();
    }

    @Transactional
    public SubscriptionSummaryResponse getSubscription(UUID tenantId) {
        tenantContext.set(tenantId);
        SetupRepository.SubscriptionSummary s = repo.getSubscription(tenantId);
        if (s == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "tenant-not-found", "Tenant not found", "Tenant not found");
        }
        int branchLimit = entitlements.branchLimit();
        int seatLimit = entitlements.seatLimit();
        var modules = entitlements.modules();
        return new SubscriptionSummaryResponse(s.plan(), s.status(), s.patientsUsed(), s.patientsLimit(),
                s.messagesUsed(), s.messagesLimit(), s.branchesUsed(), branchLimit == Integer.MAX_VALUE ? s.branchesLimit() : branchLimit,
                modules.entrySet().stream().map(e -> new SubscriptionSummaryResponse.ModuleState(e.getKey(), e.getValue())).toList(),
                modules.keySet().stream().filter(entitlements::killed).toList(),
                actors.seatsInUse(tenantId), seatLimit == Integer.MAX_VALUE ? null : (long) seatLimit);
    }

    @Transactional
    public List<ImportJobResponse> listImportJobs(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listImportJobs(tenantId).stream().map(this::toImportResponse).toList();
    }

    @Transactional
    public ImportJobResponse addImportJob(UUID tenantId, UUID callerStaffId, ImportJobRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertImportJob(tenantId, callerStaffId, req.importType(), req.fileName());
        log.info("import job {} created for tenant {} by {}", id, tenantId, callerStaffId);
        return new ImportJobResponse(id.toString(), req.importType(), req.fileName(), "pending", null, null, null);
    }

    @Transactional
    public List<ExportJobResponse> listExportJobs(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listExportJobs(tenantId).stream().map(this::toExportResponse).toList();
    }

    @Transactional
    public ExportJobResponse addExportJob(UUID tenantId, UUID callerStaffId, ExportJobRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertExportJob(tenantId, callerStaffId, req.exportType());
        log.info("export job {} created for tenant {} by {}", id, tenantId, callerStaffId);
        return new ExportJobResponse(id.toString(), req.exportType(), "pending", null, null, null);
    }

    /** ADM-032: the register, soonest expiry first; kind (licence type) and status narrow it. */
    @Transactional
    public List<LicenceResponse> listLicences(UUID tenantId, String kind, String status) {
        tenantContext.set(tenantId);
        var hidden = licences.hiddenFromBooking();
        var today = licences.today();
        return repo.listLicences(tenantId).stream()
                .filter(l -> kind == null || kind.isBlank() || kind.equals(l.licenceType()))
                .filter(l -> status == null || status.isBlank() || status.equals(l.status()))
                .map(l -> toLicenceResponse(l, today, hidden)).toList();
    }

    /** ADM-032: one register row as the API shows it (the override reads it back). Runs in the caller's tenant transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public LicenceResponse licence(UUID id) {
        var hidden = licences.hiddenFromBooking();
        var today = licences.today();
        return repo.listLicences(tenantContext.current()).stream().filter(l -> l.id().equals(id)).findFirst().map(l -> toLicenceResponse(l, today, hidden))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "licence-not-found", "Licence not found", "Licence not found"));
    }

    /** ADM-032: a holder is a staff member of this clinic (never a foreign-key 500); their name is copied into the register when none was typed. */
    private LicenceWriteRequest withHolder(UUID tenantId, LicenceWriteRequest req) {
        if (req.holderId() == null) {
            return req;
        }
        var a = actors.findActor(tenantId, req.holderId()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "holder-not-found", "Holder not found",
                "That staff member does not exist in this clinic."));
        return req.holderName() != null && !req.holderName().isBlank() ? req
                : new LicenceWriteRequest(req.licenceType(), req.holderId(), a.name(), req.number(), req.issuingBody(), req.expiryDate(), req.region(), req.certificateUploadId());
    }

    @Transactional
    public ComplianceHealthResponse complianceHealth(UUID tenantId) {
        tenantContext.set(tenantId);
        int[] c = repo.licenceCounts(tenantId);
        String status = c[2] > 0 ? "expired" : (c[1] > 0 || c[0] == 0) ? "attention" : "ok";
        String detail = c[0] == 0 ? "No licences registered"
                : c[0] + " registered, " + c[1] + " expiring within 30 days, " + c[2] + " expired";
        // shortcut: consent templates and retention settings have no tables yet, so only the licence item exists
        return new ComplianceHealthResponse(new ComplianceHealthResponse.Licences(c[0], c[1], c[2]),
                List.of(new ComplianceHealthResponse.Item("licence", "Licences", status, detail, "/setup?tab=licences")));
    }

    @Transactional
    public LicenceResponse addLicence(UUID tenantId, UUID callerStaffId, LicenceWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertLicence(tenantId, withHolder(tenantId, req));
        log.info("licence {} added for tenant {} by {}", id, tenantId, callerStaffId);
        licences.afterSave(id, callerStaffId, null, null); // status, reminders and the holder's bookability follow the saved date at once
        return licence(id);
    }

    @Transactional
    public LicenceResponse updateLicence(UUID tenantId, UUID callerStaffId, UUID id, LicenceWriteRequest req) {
        tenantContext.set(tenantId);
        java.time.LocalDate before = repo.listLicences(tenantId).stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "licence-not-found", "Licence not found", "Licence not found")).expiryDate();
        repo.updateLicence(tenantId, id, withHolder(tenantId, req));
        log.info("licence {} updated for tenant {} by {}", id, tenantId, callerStaffId);
        licences.afterSave(id, callerStaffId, null, before);
        return licence(id);
    }

    @Transactional
    public List<StaffSummaryResponse> listStaff(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listStaff(tenantId).stream()
                .map(r -> new StaffSummaryResponse(r.id(), r.name(), r.roleName()))
                .toList();
    }

    private void validateStep(String step) {
        if (!SetupSteps.ALL.contains(step)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-step", "Invalid wizard step", "Invalid wizard step: " + step);
        }
    }

    // ── Mappers ──

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.json.JsonMapper();

    private SetupChecklistItemResponse toChecklistItem(SetupProgressRow row, boolean current) {
        return new SetupChecklistItemResponse(row.step(), current ? "current" : row.status(), row.skippedAt(), row.doneAt(),
                JSON.readTree(row.draftJson()), row.version(), row.updatedAt());
    }

    private ClinicProfileResponse toProfileResponse(TenantProfileRow row) {
        return new ClinicProfileResponse(
                row.id().toString(),
                row.name(),
                row.region(),
                row.timezone(),
                row.taxId(),
                row.taxIdType(),
                row.whatsappNumber(),
                row.specialties() == null ? List.of() : Arrays.asList(row.specialties()),
                row.status(),
                row.setupCompletedAt());
    }

    private ChargeHeadResponse toChargeResponse(ChargeHeadRow row) {
        return new ChargeHeadResponse(
                row.id().toString(),
                row.code(),
                row.name(),
                row.category(),
                row.baseAmount(),
                row.followUpAmount(),
                row.emergencyAmount(),
                row.taxCode(),
                row.taxRatePercent(),
                row.doctorOverride(),
                row.active(),
                row.effectiveFrom(),
                row.effectiveTo(),
                row.displayOrder());
    }

    private PolicyResponse toPolicyResponse(PolicyRow row) {
        return new PolicyResponse(row.id().toString(), row.policyKey(), row.value(), row.version());
    }

    private ClinicHolidayResponse toHolidayResponse(HolidayRow row) {
        return CalendarService.response(row);
    }

    private StaffShiftResponse toShiftResponse(StaffShiftRow row) {
        return new StaffShiftResponse(
                row.id().toString(),
                row.staffId().toString(),
                row.staffName(),
                row.patternJson(),
                row.effectiveFrom(),
                row.effectiveTo());
    }

    private ImportJobResponse toImportResponse(SetupModels.ImportJobRow row) {
        return new ImportJobResponse(
                row.id().toString(),
                row.importType(),
                row.fileName(),
                row.status(),
                row.resultUrl(),
                row.errorMessage(),
                row.createdAt());
    }

    private ExportJobResponse toExportResponse(SetupModels.ExportJobRow row) {
        return new ExportJobResponse(
                row.id().toString(),
                row.exportType(),
                row.status(),
                row.resultUrl(),
                row.errorMessage(),
                row.createdAt());
    }

    private LicenceResponse toLicenceResponse(LicenceRow row, java.time.LocalDate today, java.util.Set<UUID> hidden) {
        long left = java.time.temporal.ChronoUnit.DAYS.between(today, row.expiryDate());
        var next = com.nabd.hms.organization.licence.LicenceLifecycleService.nextReminder(left, row.remindersSent());
        return new LicenceResponse(
                row.id().toString(),
                row.licenceType(),
                row.holderId() == null ? null : row.holderId().toString(),
                row.holderName(),
                row.number(),
                row.issuingBody(),
                row.expiryDate(),
                row.region(),
                row.status(),
                left,
                row.remindersSent().stream().sorted(java.util.Comparator.reverseOrder()).toList(),
                next.map(t -> row.expiryDate().minusDays(t)).orElse(null),
                "clinician".equals(row.licenceType()) && row.holderId() != null ? !hidden.contains(row.holderId()) : null,
                row.overrideUntil(),
                row.overrideReason(),
                row.certificateUploadId() == null ? null : row.certificateUploadId().toString());
    }
}
