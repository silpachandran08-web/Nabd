package com.nabd.hms.organization.golive;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.GoLiveCheck;
import com.nabd.hms.organization.api.RegionRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** ADM-011: the go-live checks whose data organization owns (DPA, WhatsApp number, tax identifier, market e-invoicing). */
final class OrgGoLiveChecks {

    private OrgGoLiveChecks() {
    }

    private record Row(String whatsapp, String taxId, String regionCode) {
    }

    abstract static class Base implements GoLiveCheck {
        final JdbcTemplate jdbc;
        final TenantContext tenant;
        final RegionRules regions;

        Base(JdbcTemplate jdbc, TenantContext tenant, RegionRules regions) {
            this.jdbc = jdbc;
            this.tenant = tenant;
            this.regions = regions;
        }

        Row row() {
            return jdbc.queryForObject("SELECT whatsapp_number, tax_id, region_code FROM tenants WHERE id = ?",
                    (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)), tenant.current());
        }
    }

    @Component
    static class WhatsAppSender extends Base {
        WhatsAppSender(JdbcTemplate jdbc, TenantContext tenant, RegionRules regions) {
            super(jdbc, tenant, regions);
        }

        @Override
        public boolean blocker() {
            return true;
        }

        @Override
        public int order() {
            return 30;
        }

        /** shortcut: a WhatsApp number is on file; the sender's live health (WABA status) is not read here, it has no stored state yet. */
        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Result evaluate() {
            String n = row().whatsapp();
            return new Result("whatsapp_sender", n != null && !n.isBlank(), "/setup/wizard?step=whatsapp", null);
        }
    }

    @Component
    static class DpaSigned extends Base {
        DpaSigned(JdbcTemplate jdbc, TenantContext tenant, RegionRules regions) {
            super(jdbc, tenant, regions);
        }

        @Override
        public boolean blocker() {
            return true;
        }

        @Override
        public int order() {
            return 40;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Result evaluate() {
            boolean ok = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM dpa_acceptances WHERE tenant_id = ?)", Boolean.class, tenant.current()));
            return new Result("dpa_signed", ok, "/setup/wizard?step=dpa", null);
        }
    }

    @Component
    static class TaxIdentifier extends Base {
        TaxIdentifier(JdbcTemplate jdbc, TenantContext tenant, RegionRules regions) {
            super(jdbc, tenant, regions);
        }

        @Override
        public boolean blocker() {
            return false;
        }

        @Override
        public int order() {
            return 50;
        }

        /** The label comes from the market's tax regime (GST registration or VAT registration number), never from a region code. */
        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Result evaluate() {
            Row r = row();
            String regime = regions.region(r.regionCode()).taxRegime();
            if ("NONE".equals(regime)) {
                return null;
            }
            boolean ok = r.taxId() != null && !r.taxId().isBlank();
            return new Result("tax_id", ok, null, ok ? null : "GST".equals(regime) ? "Add your GSTIN before your first tax invoice." : "Add your VAT registration number before your first tax invoice.");
        }
    }

    @Component
    static class EInvoicing extends Base {
        EInvoicing(JdbcTemplate jdbc, TenantContext tenant, RegionRules regions) {
            super(jdbc, tenant, regions);
        }

        @Override
        public boolean blocker() {
            return false;
        }

        @Override
        public int order() {
            return 60;
        }

        /** Applies where regions.einvoice_scheme is set. shortcut: no onboarding credential is stored anywhere yet, so it reads as not done until that story exists. */
        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Result evaluate() {
            List<String> scheme = jdbc.queryForList("SELECT einvoice_scheme FROM regions WHERE code = ? AND einvoice_scheme IS NOT NULL", String.class, row().regionCode());
            return scheme.isEmpty() ? null : new Result("einvoice_phase2", false, null, "phase-1 QR invoices work meanwhile");
        }
    }
}
