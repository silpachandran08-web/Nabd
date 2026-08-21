package com.nabd.hms.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.setup.dto.ChargeHeadResponse;
import com.nabd.hms.setup.dto.ChargeHeadWriteRequest;
import com.nabd.hms.setup.dto.ClinicHolidayResponse;
import com.nabd.hms.setup.dto.ClinicHolidayWriteRequest;
import com.nabd.hms.setup.dto.ClinicProfileResponse;
import com.nabd.hms.setup.dto.ClinicProfileWriteRequest;
import com.nabd.hms.setup.dto.ConsentContactResponse;
import com.nabd.hms.setup.dto.ConsentContactWriteRequest;
import com.nabd.hms.setup.dto.ExportJobRequest;
import com.nabd.hms.setup.dto.ExportJobResponse;
import com.nabd.hms.setup.dto.ImportJobRequest;
import com.nabd.hms.setup.dto.ImportJobResponse;
import com.nabd.hms.setup.dto.LicenceResponse;
import com.nabd.hms.setup.dto.LicenceWriteRequest;
import com.nabd.hms.setup.dto.PayrollExportRowResponse;
import com.nabd.hms.setup.dto.PolicyResponse;
import com.nabd.hms.setup.dto.PolicyWriteRequest;
import com.nabd.hms.setup.dto.SetupChecklistItemResponse;
import com.nabd.hms.setup.dto.SetupProgressUpdateRequest;
import com.nabd.hms.setup.dto.StaffShiftResponse;
import com.nabd.hms.setup.dto.StaffShiftWriteRequest;
import com.nabd.hms.setup.dto.SubscriptionSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.setup.SetupModels.ChargeHeadRow;
import static com.nabd.hms.setup.SetupModels.HolidayRow;
import static com.nabd.hms.setup.SetupModels.LicenceRow;
import static com.nabd.hms.setup.SetupModels.PolicyRow;
import static com.nabd.hms.setup.SetupModels.SetupProgressRow;
import static com.nabd.hms.setup.SetupModels.StaffShiftRow;
import static com.nabd.hms.setup.SetupModels.TenantProfileRow;

@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final SetupRepository repo;
    private final TenantContext tenantContext;

    SetupService(SetupRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<SetupChecklistItemResponse> getChecklist(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listProgress(tenantId).stream().map(this::toChecklistItem).toList();
    }

    @Transactional
    public void updateProgress(UUID tenantId, String step, SetupProgressUpdateRequest req) {
        tenantContext.set(tenantId);
        validateStep(step);
        repo.updateProgress(tenantId, step, req.status());
        log.info("setup progress {} marked {} for tenant {}", step, req.status(), tenantId);
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
        repo.updateProfile(tenantId, req);
        log.info("clinic profile updated for tenant {} by {}", tenantId, callerStaffId);
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
        return repo.findCharge(tenantId, id).map(this::toChargeResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "charge-not-found", "Charge not found after insert", "Charge not found after insert"));
    }

    @Transactional
    public ChargeHeadResponse updateCharge(UUID tenantId, UUID callerStaffId, UUID id, ChargeHeadWriteRequest req) {
        tenantContext.set(tenantId);
        repo.findCharge(tenantId, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "charge-not-found", "Charge not found", "Charge not found"));
        repo.updateCharge(tenantId, id, req);
        log.info("charge head {} updated for tenant {} by {}", id, tenantId, callerStaffId);
        return repo.findCharge(tenantId, id).map(this::toChargeResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "charge-not-found", "Charge not found after update", "Charge not found after update"));
    }

    @Transactional
    public List<PolicyResponse> listPolicies(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listPolicies(tenantId).stream().map(this::toPolicyResponse).toList();
    }

    @Transactional
    public PolicyResponse updatePolicy(UUID tenantId, UUID callerStaffId, String policyKey, PolicyWriteRequest req) {
        tenantContext.set(tenantId);
        repo.updatePolicy(tenantId, policyKey, req.value());
        log.info("policy {} updated for tenant {} by {}", policyKey, tenantId, callerStaffId);
        return repo.listPolicies(tenantId).stream()
                .filter(p -> p.policyKey().equals(policyKey))
                .findFirst()
                .map(this::toPolicyResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "policy-not-found", "Policy not found", "Policy not found"));
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
        log.info("consent contact updated for tenant {} by {}", tenantId, callerStaffId);
        return new ConsentContactResponse(req.name(), req.email(), req.phone());
    }

    @Transactional
    public List<ClinicHolidayResponse> listHolidays(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listHolidays(tenantId).stream().map(this::toHolidayResponse).toList();
    }

    @Transactional
    public ClinicHolidayResponse addHoliday(UUID tenantId, UUID callerStaffId, ClinicHolidayWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertHoliday(tenantId, req);
        log.info("holiday {} added for tenant {} by {}", id, tenantId, callerStaffId);
        return new ClinicHolidayResponse(id.toString(), req.holidayDate(), req.name(), req.recurring());
    }

    @Transactional
    public void deleteHoliday(UUID tenantId, UUID callerStaffId, UUID id) {
        tenantContext.set(tenantId);
        int deleted = repo.deleteHoliday(tenantId, id);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "holiday-not-found", "Holiday not found", "Holiday not found");
        }
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
            month = monthParam == null ? YearMonth.now() : YearMonth.parse(monthParam);
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
        return new SubscriptionSummaryResponse(s.plan(), s.status(), s.patientsUsed(), s.patientsLimit(),
                s.messagesUsed(), s.messagesLimit(), s.branchesUsed(), s.branchesLimit());
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

    @Transactional
    public List<LicenceResponse> listLicences(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listLicences(tenantId).stream().map(this::toLicenceResponse).toList();
    }

    @Transactional
    public LicenceResponse addLicence(UUID tenantId, UUID callerStaffId, LicenceWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertLicence(tenantId, req);
        log.info("licence {} added for tenant {} by {}", id, tenantId, callerStaffId);
        return repo.listLicences(tenantId).stream()
                .filter(l -> l.id().equals(id))
                .findFirst()
                .map(this::toLicenceResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "licence-not-found", "Licence not found after insert", "Licence not found after insert"));
    }

    @Transactional
    public LicenceResponse updateLicence(UUID tenantId, UUID callerStaffId, UUID id, LicenceWriteRequest req) {
        tenantContext.set(tenantId);
        repo.updateLicence(tenantId, id, req);
        log.info("licence {} updated for tenant {} by {}", id, tenantId, callerStaffId);
        return repo.listLicences(tenantId).stream()
                .filter(l -> l.id().equals(id))
                .findFirst()
                .map(this::toLicenceResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "licence-not-found", "Licence not found", "Licence not found"));
    }

    @Transactional
    public List<SetupModels.StaffSummaryRow> listStaff(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listStaff(tenantId);
    }

    private void validateStep(String step) {
        List<String> valid = List.of("welcome", "profile", "tax", "doctors", "schedule", "charges", "pharmacy", "whatsapp", "go_live");
        if (!valid.contains(step)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-step", "Invalid wizard step", "Invalid wizard step: " + step);
        }
    }

    // ── Mappers ──

    private SetupChecklistItemResponse toChecklistItem(SetupProgressRow row) {
        return new SetupChecklistItemResponse(row.step(), row.status(), row.skippedAt(), row.doneAt());
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
        return new ClinicHolidayResponse(row.id().toString(), row.holidayDate(), row.name(), row.recurring());
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

    private LicenceResponse toLicenceResponse(LicenceRow row) {
        return new LicenceResponse(
                row.id().toString(),
                row.licenceType(),
                row.holderId() == null ? null : row.holderId().toString(),
                row.holderName(),
                row.number(),
                row.issuingBody(),
                row.expiryDate(),
                row.region(),
                row.status());
    }
}
