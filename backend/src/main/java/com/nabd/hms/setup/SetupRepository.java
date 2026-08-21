package com.nabd.hms.setup;

import com.nabd.hms.setup.dto.ChargeHeadWriteRequest;
import com.nabd.hms.setup.dto.ClinicHolidayWriteRequest;
import com.nabd.hms.setup.dto.ClinicProfileWriteRequest;
import com.nabd.hms.setup.dto.ConsentContactWriteRequest;
import com.nabd.hms.setup.dto.LicenceWriteRequest;
import com.nabd.hms.setup.dto.StaffShiftWriteRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.setup.SetupModels.AttendanceRow;
import static com.nabd.hms.setup.SetupModels.ChargeHeadRow;
import static com.nabd.hms.setup.SetupModels.ConsentContactRow;
import static com.nabd.hms.setup.SetupModels.ExportJobRow;
import static com.nabd.hms.setup.SetupModels.HolidayRow;
import static com.nabd.hms.setup.SetupModels.ImportJobRow;
import static com.nabd.hms.setup.SetupModels.LicenceRow;
import static com.nabd.hms.setup.SetupModels.PolicyRow;
import static com.nabd.hms.setup.SetupModels.SetupProgressRow;
import static com.nabd.hms.setup.SetupModels.StaffShiftRow;
import static com.nabd.hms.setup.SetupModels.StaffSummaryRow;
import static com.nabd.hms.setup.SetupModels.TenantProfileRow;

@Repository
class SetupRepository {

    private final JdbcTemplate jdbc;

    SetupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Wizard progress ──

    List<SetupProgressRow> listProgress(UUID tenantId) {
        ensureProgressSeeded(tenantId);
        return jdbc.query(
                "SELECT id, step, status, skipped_at, done_at FROM clinic_setup_progress " +
                        "WHERE tenant_id = ? ORDER BY array_position(ARRAY['welcome','profile','tax','doctors','schedule','charges','pharmacy','whatsapp','go_live'], step)",
                progressMapper(), tenantId);
    }

    void updateProgress(UUID tenantId, String step, String status) {
        ensureProgressSeeded(tenantId);
        jdbc.update("""
                UPDATE clinic_setup_progress
                SET status = ?,
                    skipped_at = CASE WHEN ? = 'skipped' THEN now() ELSE NULL END,
                    done_at = CASE WHEN ? = 'done' THEN now() ELSE NULL END,
                    updated_at = now()
                WHERE tenant_id = ? AND step = ?
                """,
                status, status, status, tenantId, step);
    }

    private void ensureProgressSeeded(UUID tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM clinic_setup_progress WHERE tenant_id = ?", Long.class, tenantId);
        if (count != null && count > 0) {
            return;
        }
        String[] steps = {"welcome", "profile", "tax", "doctors", "schedule", "charges", "pharmacy", "whatsapp", "go_live"};
        for (String step : steps) {
            jdbc.update("INSERT INTO clinic_setup_progress (tenant_id, step, status) VALUES (?, ?, 'pending')",
                    tenantId, step);
        }
    }

    // ── Clinic profile ──

    Optional<TenantProfileRow> findProfile(UUID tenantId) {
        return jdbc.query(
                "SELECT id, name, region, timezone, tax_id, tax_id_type, whatsapp_number, specialties, status, setup_completed_at " +
                        "FROM tenants WHERE id = ?", profileMapper(), tenantId).stream().findFirst();
    }

    void updateProfile(UUID tenantId, ClinicProfileWriteRequest req) {
        jdbc.update("UPDATE tenants SET name = ?, timezone = ?, tax_id = ?, tax_id_type = ?, whatsapp_number = ?, specialties = ?::text[], updated_at = now() WHERE id = ?",
                req.name(), req.timezone(), req.taxId(), req.taxIdType(), req.whatsappNumber(),
                req.specialties() == null ? new String[0] : req.specialties().toArray(new String[0]),
                tenantId);
    }

    void markSetupCompleted(UUID tenantId) {
        jdbc.update("UPDATE tenants SET setup_completed_at = now(), updated_at = now() WHERE id = ?", tenantId);
    }

    // ── Charge catalogue ──

    List<ChargeHeadRow> listCharges(UUID tenantId) {
        return jdbc.query(
                "SELECT id, code, name, category, base_amount, follow_up_amount, emergency_amount, tax_code, " +
                        "tax_rate_percent, doctor_override, active, effective_from, effective_to, display_order " +
                        "FROM charge_catalogue WHERE tenant_id = ? ORDER BY display_order, name",
                chargeMapper(), tenantId);
    }

    Optional<ChargeHeadRow> findCharge(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, code, name, category, base_amount, follow_up_amount, emergency_amount, tax_code, " +
                        "tax_rate_percent, doctor_override, active, effective_from, effective_to, display_order " +
                        "FROM charge_catalogue WHERE tenant_id = ? AND id = ?",
                chargeMapper(), tenantId, id).stream().findFirst();
    }

    UUID insertCharge(UUID tenantId, ChargeHeadWriteRequest req) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge_catalogue (id, tenant_id, code, name, category, base_amount, follow_up_amount, " +
                        "emergency_amount, tax_code, tax_rate_percent, doctor_override, active, effective_from, effective_to, display_order) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, req.code(), req.name(), req.category(), req.baseAmount(), req.followUpAmount(),
                req.emergencyAmount(), req.taxCode(), req.taxRatePercentOrZero(), req.doctorOverride(), req.active(),
                Date.valueOf(req.effectiveFrom()), req.effectiveTo() == null ? null : Date.valueOf(req.effectiveTo()),
                req.displayOrder());
        return id;
    }

    void updateCharge(UUID tenantId, UUID id, ChargeHeadWriteRequest req) {
        jdbc.update("UPDATE charge_catalogue SET code = ?, name = ?, category = ?, base_amount = ?, follow_up_amount = ?, " +
                        "emergency_amount = ?, tax_code = ?, tax_rate_percent = ?, doctor_override = ?, active = ?, effective_from = ?, " +
                        "effective_to = ?, display_order = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                req.code(), req.name(), req.category(), req.baseAmount(), req.followUpAmount(),
                req.emergencyAmount(), req.taxCode(), req.taxRatePercentOrZero(), req.doctorOverride(), req.active(),
                Date.valueOf(req.effectiveFrom()), req.effectiveTo() == null ? null : Date.valueOf(req.effectiveTo()),
                req.displayOrder(), tenantId, id);
    }

    // ── Policies ──

    List<PolicyRow> listPolicies(UUID tenantId) {
        ensurePoliciesSeeded(tenantId);
        return jdbc.query("SELECT id, policy_key, value, version FROM clinic_policies WHERE tenant_id = ? ORDER BY policy_key",
                policyMapper(), tenantId);
    }

    void updatePolicy(UUID tenantId, String policyKey, String value) {
        ensurePoliciesSeeded(tenantId);
        jdbc.update("UPDATE clinic_policies SET value = ?, version = version + 1, updated_at = now() WHERE tenant_id = ? AND policy_key = ?",
                value, tenantId, policyKey);
    }

    private void ensurePoliciesSeeded(UUID tenantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM clinic_policies WHERE tenant_id = ?", Long.class, tenantId);
        if (count != null && count > 0) {
            return;
        }
        String[][] defaults = {
                {"cancellation_window_hours", "2"},
                {"no_show_fee_amount", "0"},
                {"reminder_hours_before", "24,2"},
                {"refund_rule", "Full refund on unused sessions"},
                {"appointment_buffer_minutes", "15"}
        };
        for (String[] d : defaults) {
            jdbc.update("INSERT INTO clinic_policies (tenant_id, policy_key, value) VALUES (?, ?, ?)",
                    tenantId, d[0], d[1]);
        }
    }

    // ── Consent contact ──

    Optional<ConsentContactRow> findConsentContact(UUID tenantId) {
        return jdbc.query(
                "SELECT consent_contact_name, consent_contact_email, consent_contact_phone FROM tenants WHERE id = ?",
                (rs, i) -> new ConsentContactRow(rs.getString(1), rs.getString(2), rs.getString(3)),
                tenantId).stream().findFirst();
    }

    void updateConsentContact(UUID tenantId, ConsentContactWriteRequest req) {
        jdbc.update("UPDATE tenants SET consent_contact_name = ?, consent_contact_email = ?, consent_contact_phone = ?, updated_at = now() WHERE id = ?",
                req.name(), req.email(), req.phone(), tenantId);
    }

    // ── Holidays ──

    List<HolidayRow> listHolidays(UUID tenantId) {
        return jdbc.query("SELECT id, holiday_date, name, recurring FROM clinic_holidays WHERE tenant_id = ? ORDER BY holiday_date",
                holidayMapper(), tenantId);
    }

    UUID insertHoliday(UUID tenantId, ClinicHolidayWriteRequest req) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO clinic_holidays (id, tenant_id, holiday_date, name, recurring) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, Date.valueOf(req.holidayDate()), req.name(), req.recurring());
        return id;
    }

    int deleteHoliday(UUID tenantId, UUID id) {
        return jdbc.update("DELETE FROM clinic_holidays WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    // ── Shifts ──

    List<StaffShiftRow> listShifts(UUID tenantId) {
        return jdbc.query(
                "SELECT ss.id, ss.staff_id, s.name, ss.pattern_json, ss.effective_from, ss.effective_to " +
                        "FROM staff_shifts ss JOIN staff s ON s.id = ss.staff_id " +
                        "WHERE ss.tenant_id = ? ORDER BY s.name, ss.effective_from",
                shiftMapper(), tenantId);
    }

    UUID insertShift(UUID tenantId, StaffShiftWriteRequest req) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO staff_shifts (id, tenant_id, staff_id, pattern_json, effective_from, effective_to) " +
                        "VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                id, tenantId, req.staffId(), req.patternJson(), Date.valueOf(req.effectiveFrom()),
                req.effectiveTo() == null ? null : Date.valueOf(req.effectiveTo()));
        return id;
    }

    // ── Payroll export ──

    List<AttendanceRow> payrollExport(UUID tenantId, YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return jdbc.query(
                "SELECT s.id, s.name, r.name AS role_name, " +
                        "count(ar.id) FILTER (WHERE ar.check_in IS NOT NULL) AS days, " +
                        "coalesce(sum(extract(epoch from (ar.check_out - ar.check_in)) / 3600.0), 0) AS hours, " +
                        "0::numeric AS salary, '' AS notes " +
                        "FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "LEFT JOIN attendance_records ar ON ar.staff_id = s.id AND ar.tenant_id = s.tenant_id " +
                        "AND ar.record_date BETWEEN ? AND ? " +
                        "WHERE s.tenant_id = ? GROUP BY s.id, s.name, r.name ORDER BY s.name",
                attendanceMapper(), Date.valueOf(start), Date.valueOf(end), tenantId);
    }

    // ── Subscription ──

    // ponytail: 'plan' is a fixed placeholder, not tenants.name — there's no plan/tier column
    // anywhere in the schema yet (that's NB-269, pricing & packaging, not built). branches_used
    // is correlated on tenants.brand_id directly: a brand-less tenant is its own one branch
    // (brand_id = NULL never equals anything in a subquery, so the naive join undercounts to 0).
    SubscriptionSummary getSubscription(UUID tenantId) {
        return jdbc.query(
                "SELECT status, " +
                        "(SELECT count(*) FROM patients WHERE tenant_id = tenants.id AND status = 'active') AS patients_used, " +
                        "500 AS patients_limit, " +
                        "0 AS messages_used, " +
                        "3000 AS messages_limit, " +
                        "CASE WHEN brand_id IS NULL THEN 1 " +
                        "     ELSE (SELECT count(*) FROM tenants t2 WHERE t2.brand_id = tenants.brand_id) END AS branches_used, " +
                        "1 AS branches_limit " +
                        "FROM tenants WHERE id = ?",
                (rs, i) -> new SubscriptionSummary(
                        "Standard",
                        rs.getString("status"),
                        rs.getLong("patients_used"),
                        rs.getLong("patients_limit"),
                        rs.getLong("messages_used"),
                        rs.getLong("messages_limit"),
                        rs.getLong("branches_used"),
                        rs.getLong("branches_limit")
                ), tenantId).stream().findFirst().orElse(null);
    }

    record SubscriptionSummary(String plan, String status, long patientsUsed, long patientsLimit,
                               long messagesUsed, long messagesLimit, long branchesUsed, long branchesLimit) {
    }

    // ── Import / Export jobs ──

    List<ImportJobRow> listImportJobs(UUID tenantId) {
        return jdbc.query(
                "SELECT id, import_type, file_name, status, result_url, error_message, created_at " +
                        "FROM data_import_jobs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50",
                importJobMapper(), tenantId);
    }

    UUID insertImportJob(UUID tenantId, UUID staffId, String importType, String fileName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO data_import_jobs (id, tenant_id, import_type, file_name, requested_by) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, importType, fileName, staffId);
        return id;
    }

    List<ExportJobRow> listExportJobs(UUID tenantId) {
        return jdbc.query(
                "SELECT id, export_type, status, result_url, error_message, created_at " +
                        "FROM data_export_jobs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50",
                exportJobMapper(), tenantId);
    }

    UUID insertExportJob(UUID tenantId, UUID staffId, String exportType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO data_export_jobs (id, tenant_id, export_type, requested_by) VALUES (?, ?, ?, ?)",
                id, tenantId, exportType, staffId);
        return id;
    }

    // ── Licence registry ──

    List<LicenceRow> listLicences(UUID tenantId) {
        return jdbc.query(
                "SELECT id, licence_type, holder_id, holder_name, number, issuing_body, expiry_date, region, status " +
                        "FROM licence_registry WHERE tenant_id = ? ORDER BY licence_type, holder_name",
                licenceMapper(), tenantId);
    }

    UUID insertLicence(UUID tenantId, LicenceWriteRequest req) {
        UUID id = UUID.randomUUID();
        String status = computeLicenceStatus(req.expiryDate());
        jdbc.update("INSERT INTO licence_registry (id, tenant_id, licence_type, holder_id, holder_name, number, issuing_body, expiry_date, region, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, req.licenceType(), req.holderId(), req.holderName(), req.number(),
                req.issuingBody(), Date.valueOf(req.expiryDate()), req.region(), status);
        return id;
    }

    void updateLicence(UUID tenantId, UUID id, LicenceWriteRequest req) {
        String status = computeLicenceStatus(req.expiryDate());
        jdbc.update("UPDATE licence_registry SET licence_type = ?, holder_id = ?, holder_name = ?, number = ?, " +
                        "issuing_body = ?, expiry_date = ?, region = ?, status = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                req.licenceType(), req.holderId(), req.holderName(), req.number(), req.issuingBody(),
                Date.valueOf(req.expiryDate()), req.region(), status, tenantId, id);
    }

    private String computeLicenceStatus(LocalDate expiry) {
        LocalDate now = LocalDate.now();
        if (expiry.isBefore(now)) {
            return "expired";
        }
        if (expiry.isBefore(now.plusDays(30))) {
            return "expiring_soon";
        }
        return "valid";
    }

    // ── Staff lookup helper ──

    List<StaffSummaryRow> listStaff(UUID tenantId) {
        return jdbc.query(
                "SELECT s.id, s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.status = 'active' ORDER BY s.name",
                (rs, i) -> new StaffSummaryRow(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("role_name")),
                tenantId);
    }

    // ── Row mappers ──

    private RowMapper<SetupProgressRow> progressMapper() {
        return (rs, i) -> new SetupProgressRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("step"),
                rs.getString("status"),
                rs.getTimestamp("skipped_at") == null ? null : rs.getTimestamp("skipped_at").toInstant(),
                rs.getTimestamp("done_at") == null ? null : rs.getTimestamp("done_at").toInstant());
    }

    private RowMapper<TenantProfileRow> profileMapper() {
        return (rs, i) -> {
            Array specArray = rs.getArray("specialties");
            String[] specialties = specArray == null ? new String[0] : (String[]) specArray.getArray();
            return new TenantProfileRow(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("name"),
                    rs.getString("region"),
                    rs.getString("timezone"),
                    rs.getString("tax_id"),
                    rs.getString("tax_id_type"),
                    rs.getString("whatsapp_number"),
                    specialties,
                    rs.getString("status"),
                    rs.getTimestamp("setup_completed_at") == null ? null : rs.getTimestamp("setup_completed_at").toInstant());
        };
    }

    private RowMapper<ChargeHeadRow> chargeMapper() {
        return (rs, i) -> new ChargeHeadRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getBigDecimal("base_amount"),
                rs.getBigDecimal("follow_up_amount"),
                rs.getBigDecimal("emergency_amount"),
                rs.getString("tax_code"),
                rs.getBigDecimal("tax_rate_percent"),
                rs.getBoolean("doctor_override"),
                rs.getBoolean("active"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
                rs.getInt("display_order"));
    }

    private RowMapper<PolicyRow> policyMapper() {
        return (rs, i) -> new PolicyRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("policy_key"),
                rs.getString("value"),
                rs.getInt("version"));
    }

    private RowMapper<HolidayRow> holidayMapper() {
        return (rs, i) -> new HolidayRow(
                UUID.fromString(rs.getString("id")),
                rs.getDate("holiday_date").toLocalDate(),
                rs.getString("name"),
                rs.getBoolean("recurring"));
    }

    private RowMapper<StaffShiftRow> shiftMapper() {
        return (rs, i) -> new StaffShiftRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("staff_id")),
                rs.getString("name"),
                rs.getString("pattern_json"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate());
    }

    private RowMapper<AttendanceRow> attendanceMapper() {
        return (rs, i) -> new AttendanceRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("role_name"),
                rs.getLong("days"),
                rs.getBigDecimal("hours"),
                rs.getBigDecimal("salary"),
                rs.getString("notes"));
    }

    private RowMapper<ImportJobRow> importJobMapper() {
        return (rs, i) -> new ImportJobRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("import_type"),
                rs.getString("file_name"),
                rs.getString("status"),
                rs.getString("result_url"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant());
    }

    private RowMapper<ExportJobRow> exportJobMapper() {
        return (rs, i) -> new ExportJobRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("export_type"),
                rs.getString("status"),
                rs.getString("result_url"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant());
    }

    private RowMapper<LicenceRow> licenceMapper() {
        return (rs, i) -> new LicenceRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("licence_type"),
                rs.getString("holder_id") == null ? null : UUID.fromString(rs.getString("holder_id")),
                rs.getString("holder_name"),
                rs.getString("number"),
                rs.getString("issuing_body"),
                rs.getDate("expiry_date").toLocalDate(),
                rs.getString("region"),
                rs.getString("status"));
    }
}
