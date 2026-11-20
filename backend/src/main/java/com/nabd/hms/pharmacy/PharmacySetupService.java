package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.InvoicingGate;
import com.nabd.hms.pharmacy.dto.PharmacySetupResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-032: the pharmacy setup wizard's status, the receipt-printer test and the licence gate on invoicing. The licence numbers
 * live where PHL-004 put them (pharmacy_settings.drug_licence_numbers), so the wizard writes them through PATCH /v1/pharmacy/settings.
 */
@Service
class PharmacySetupService implements InvoicingGate {

    /** Key under which the wizard keeps the pharmacist-in-charge's registration number in the licence map. */
    static final String PIC_REGISTRATION_KEY = "PIC_REG_NO";
    /** India: retail sale licence (Form 20B) and (Form 21B). NEEDS REGULATORY CHECK (which forms a standalone chemist must hold). */
    private static final List<String> INDIA_KEYS = List.of("IN_20B", "IN_21B");
    /** KSA SFDA establishment licence; the same key is asked in AE and QA until a regulatory expert names those regulators' licences. */
    private static final List<String> GULF_KEYS = List.of("SFDA_ESTABLISHMENT");

    private final PharmacyRepository repo;
    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;

    PharmacySetupService(PharmacyRepository repo, JdbcTemplate jdbc, TenantContext tenantContext, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
    }

    record Facility(String type, String region) {
    }

    /** tenants carries no RLS. */
    private Facility facility(UUID tenantId) {
        return jdbc.query("SELECT facility_type, region_code FROM tenants WHERE id = ?",
                (rs, i) -> new Facility(rs.getString(1), rs.getString(2)), tenantId).stream().findFirst().orElse(new Facility("clinic", null));
    }

    static List<String> requiredKeys(String region) {
        return "IN".equals(region) ? INDIA_KEYS : GULF_KEYS;
    }

    private static boolean filled(Map<String, String> m, String key) {
        String v = m.get(key);
        return v != null && !v.isBlank();
    }

    @Transactional(readOnly = true)
    public PharmacySetupResponse status(UUID tenantId) {
        tenantContext.set(tenantId);
        Facility f = facility(tenantId);
        var settings = repo.findSettings(tenantId);
        Map<String, String> licences = settings.map(s -> PharmacyService.licences(s.licenceJson())).orElse(Map.of());
        List<String> required = requiredKeys(f.region());
        List<String> missing = required.stream().filter(k -> !filled(licences, k)).toList();
        boolean pharmacy = "pharmacy".equals(f.type());
        Integer stocked = jdbc.queryForObject("SELECT count(*) FROM charge_catalogue WHERE tenant_id = ? AND stock_qty > 0", Integer.class, tenantId);
        Instant tested = jdbc.queryForList("SELECT printer_tested_at FROM pharmacy_settings WHERE tenant_id = ?", java.sql.Timestamp.class, tenantId)
                .stream().filter(java.util.Objects::nonNull).findFirst().map(java.sql.Timestamp::toInstant).orElse(null);
        return new PharmacySetupResponse(pharmacy, f.region(), required, missing,
                settings.map(s -> s.pharmacistInChargeStaffId() != null).orElse(false), filled(licences, PIC_REGISTRATION_KEY), tested,
                stocked == null ? 0 : stocked, pharmacy && !missing.isEmpty());
    }

    /** Records that the owner printed the test page on the receipt printer; the page itself is printed by the browser. */
    @Transactional
    public Instant recordPrinterTest(UUID tenantId, UUID staffId, String ip) {
        tenantContext.set(tenantId);
        var cur = repo.findSettings(tenantId);
        if (cur.isEmpty()) repo.saveSettings(tenantId, "external", false, null, "{}", null, null); // a clinic that never opened the settings
        Instant now = jdbc.queryForObject("UPDATE pharmacy_settings SET printer_tested_at = now() WHERE tenant_id = ? RETURNING printer_tested_at",
                java.sql.Timestamp.class, tenantId).toInstant();
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(() -> ApiException.notFound("Staff member not found."));
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "pharmacy.printer_tested", "pharmacy_settings", tenantId, null, null);
        return now;
    }

    @Override
    public void requireReady() {
        UUID tenantId = tenantContext.current();
        Facility f = facility(tenantId);
        if (!"pharmacy".equals(f.type())) return;
        Map<String, String> licences = repo.findSettings(tenantId).map(s -> PharmacyService.licences(s.licenceJson())).orElse(Map.of());
        if (!requiredKeys(f.region()).stream().allMatch(k -> filled(licences, k))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "drug-licence-missing", "DRUG_LICENCE_MISSING",
                    "Add your drug licence number in Pharmacy setup before issuing invoices.");
        }
    }
}
