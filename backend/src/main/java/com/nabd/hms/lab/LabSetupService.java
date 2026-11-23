package com.nabd.hms.lab;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.lab.LabDtos.TestPatch;
import com.nabd.hms.lab.LabSetupController.PriceImportRequest;
import com.nabd.hms.lab.LabSetupController.PriceImportResult;
import com.nabd.hms.lab.LabSetupController.PriceLine;
import com.nabd.hms.lab.LabSetupController.Problem;
import com.nabd.hms.lab.LabSetupController.Setup;
import com.nabd.hms.lab.LabSetupController.SetupRequest;
import com.nabd.hms.organization.api.ChargeCatalogue;
import com.nabd.hms.organization.api.ChargeCatalogue.Charge;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-056: lab_settings (V3650) and the price list import. A price is never written here: each priced test goes through
 * LabCatalogueService.updateTest, so the price-list head, the FIN-016 price change log, the audit row and the catalogue event
 * are the same as for a price typed in the catalogue. The tenant is the one bound to the transaction.
 */
@Service
public class LabSetupService {

    static final int MAX_LINES = 5000;
    static final int MAX_LETTERHEAD_LINES = 6;
    private static final String CODE = "^[A-Z0-9][A-Z0-9_-]{0,23}$";
    private static final String AMOUNT = "^[0-9]{1,10}(\\.[0-9]{1,2})?$";
    /** Splits on commas outside double quotes, so "1,250.00" stays one cell. */
    private static final String CELLS = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";

    private record Row(String accreditationNo, UUID pathologistStaffId, String pathologistRegistrationNo, String letterheadLines,
                       Instant printerTestedAt) {
        static final Row NONE = new Row(null, null, null, null, null);
    }

    private record Test(UUID id, String name, UUID chargeItemId, boolean active) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final ChargeCatalogue charges;
    private final LabCatalogueService catalogue;

    LabSetupService(JdbcTemplate jdbc, TenantContext tenantContext, ActorDirectory actors, AuditService audit, ChargeCatalogue charges,
                    LabCatalogueService catalogue) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.audit = audit;
        this.charges = charges;
        this.catalogue = catalogue;
    }

    @Transactional(readOnly = true)
    public Setup status() {
        UUID tenantId = tenantContext.current();
        return view(tenantId, row(tenantId));
    }

    @Transactional
    public Setup save(UUID actorId, String ip, SetupRequest req) {
        UUID tenantId = tenantContext.current();
        String letterhead = blankToNull(req.letterheadLines());
        if (letterhead != null && letterhead.lines().count() > MAX_LETTERHEAD_LINES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "The letterhead takes up to " + MAX_LETTERHEAD_LINES + " lines under the lab's name.");
        }
        if (req.pathologistStaffId() != null && actors.findActor(tenantId, req.pathologistStaffId()).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-staff", "Unknown staff member",
                    "Choose a staff member of this lab as the pathologist.");
        }
        Row before = row(tenantId);
        Row after = new Row(blankToNull(req.accreditationNo()), req.pathologistStaffId(), blankToNull(req.pathologistRegistrationNo()),
                letterhead, before.printerTestedAt());
        jdbc.update("INSERT INTO lab_settings (tenant_id, accreditation_no, pathologist_staff_id, pathologist_registration_no, letterhead_lines) "
                        + "VALUES (?,?,?,?,?) ON CONFLICT (tenant_id) DO UPDATE SET accreditation_no = EXCLUDED.accreditation_no, "
                        + "pathologist_staff_id = EXCLUDED.pathologist_staff_id, pathologist_registration_no = EXCLUDED.pathologist_registration_no, "
                        + "letterhead_lines = EXCLUDED.letterhead_lines, updated_at = now()",
                tenantId, after.accreditationNo(), after.pathologistStaffId(), after.pathologistRegistrationNo(), after.letterheadLines());
        record(tenantId, actorId, ip, "lab.setup_updated", facts(before), facts(after));
        return view(tenantId, after);
    }

    @Transactional
    public Instant recordPrinterTest(UUID actorId, String ip) {
        UUID tenantId = tenantContext.current();
        Instant at = jdbc.queryForObject("INSERT INTO lab_settings (tenant_id, printer_tested_at) VALUES (?, now()) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET printer_tested_at = now(), updated_at = now() RETURNING printer_tested_at",
                Timestamp.class, tenantId).toInstant();
        record(tenantId, actorId, ip, "lab.printer_tested", null, null);
        return at;
    }

    @Transactional
    public PriceImportResult importPrices(UUID actorId, String ip, PriceImportRequest req) {
        UUID tenantId = tenantContext.current();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        String[] lines = req.csv().split("\\r?\\n", -1);
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] c = l.split(CELLS, -1);
            String code = cell(c[0]).toUpperCase(Locale.ROOT);
            boolean header = first && "CODE".equals(code);
            first = false;
            if (header) continue;
            String amount = c.length < 2 ? "" : cell(c[1]).replace(",", "");
            if (code.matches(CODE) && amount.isEmpty()) continue; // a line of the downloaded template the lab has not priced yet
            if (!code.matches(CODE)) {
                problems.add(new Problem(i + 1, "Line " + (i + 1) + " does not start with a test code."));
            } else if (!amount.matches(AMOUNT) || new BigDecimal(amount).signum() <= 0) {
                problems.add(new Problem(i + 1, "Line " + (i + 1) + ": the price of " + code + " must be an amount above zero, like 250 or 250.50."));
            } else if (prices.putIfAbsent(code, new BigDecimal(amount)) != null) {
                problems.add(new Problem(i + 1, "Line " + (i + 1) + ": " + code + " is listed twice; the first price is used."));
            }
            if (prices.size() + problems.size() > MAX_LINES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                        "A price list takes up to " + MAX_LINES + " lines. Split the file and import each part.");
            }
        }
        Map<String, Test> tests = new HashMap<>();
        jdbc.query("SELECT id, code, name, charge_item_id, active FROM lab_tests WHERE tenant_id = ? AND code = ANY (?)",
                rs -> {
                    tests.put(rs.getString("code"), new Test(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getObject("charge_item_id", UUID.class), rs.getBoolean("active")));
                }, tenantId, prices.keySet().toArray(new String[0]));
        Map<UUID, Charge> heads = charges.find(tests.values().stream().map(Test::chargeItemId).toList());
        List<PriceLine> toPrice = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int unchanged = 0;
        for (Map.Entry<String, BigDecimal> e : prices.entrySet()) {
            Test t = tests.get(e.getKey());
            if (t == null) {
                unknown.add(e.getKey());
                continue;
            }
            BigDecimal current = heads.get(t.chargeItemId()).baseAmount();
            if (t.active() && current.compareTo(e.getValue()) == 0) {
                unchanged++;
                continue;
            }
            toPrice.add(new PriceLine(e.getKey(), t.name(), current, e.getValue()));
            if (!req.dryRun()) {
                catalogue.updateTest(actorId, ip, t.id(), new TestPatch(null, null, null, null, null, null, null, null, e.getValue(), null, true));
            }
        }
        if (!req.dryRun() && !toPrice.isEmpty()) {
            record(tenantId, actorId, ip, "lab.prices_imported", null,
                    Map.of("priced", toPrice.size(), "unchanged", unchanged, "unknown", unknown.size(), "problems", problems.size()));
        }
        return new PriceImportResult(req.dryRun(), toPrice, unchanged, unknown, problems, req.dryRun() ? 0 : toPrice.size());
    }

    private Row row(UUID tenantId) {
        return jdbc.query("SELECT accreditation_no, pathologist_staff_id, pathologist_registration_no, letterhead_lines, printer_tested_at "
                        + "FROM lab_settings WHERE tenant_id = ?",
                (rs, i) -> new Row(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()), tenantId).stream().findFirst().orElse(Row.NONE);
    }

    private Setup view(UUID tenantId, Row r) {
        String pathologist = r.pathologistStaffId() == null ? null
                : actors.findActor(tenantId, r.pathologistStaffId()).map(ActorDirectory.Actor::name).orElse(null);
        Map<String, Object> n = jdbc.queryForMap("SELECT count(*) AS total, count(*) FILTER (WHERE active) AS active FROM lab_tests WHERE tenant_id = ?", tenantId);
        return new Setup(r.accreditationNo(), r.pathologistStaffId(), pathologist, r.pathologistRegistrationNo(), r.letterheadLines(),
                r.printerTestedAt(), ((Number) n.get("total")).intValue(), ((Number) n.get("active")).intValue());
    }

    private static Map<String, Object> facts(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accreditationNo", r.accreditationNo());
        m.put("pathologistStaffId", r.pathologistStaffId());
        m.put("pathologistRegistrationSet", r.pathologistRegistrationNo() != null); // the number itself stays out of the audit log
        m.put("letterheadLines", r.letterheadLines());
        return m;
    }

    private void record(UUID tenantId, UUID actorId, String ip, String action, Object before, Object after) {
        String[] a = actors.findActor(tenantId, actorId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", actorId, a[0], a[1], ip, action, "lab_settings", tenantId, before, after);
    }

    private static String cell(String v) {
        String t = v.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1).trim() : t;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
