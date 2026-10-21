package com.nabd.hms.patient;

import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.ConsentRegistry.Purpose;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IDC-002: purposes default-deny, consents join exactly one immutable notice row, tenant isolation, ADR content. */
class Idc002ConsentModelApiTest extends ApiTestBase {

    @Autowired ConsentRegistry registry;

    private UUID patient(UUID tenantId) {
        UUID id = UUID.randomUUID();
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,'1990-01-01','female')",
                id, tenantId, "Consent Test", "+9198" + (10000000 + (int) (Math.random() * 89999999))));
        return id;
    }

    private void notice(UUID tenantId, String version, String lang, String effective) {
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) " +
                "VALUES (?,?,?,'IN-DPDP-1','body','h1',?::timestamptz)", tenantId, version, lang, effective));
    }

    private void consent(UUID tenantId, UUID patientId, String type, boolean withdrawn, String version, String lang, String channel) {
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at, notice_version, language, channel) " +
                "VALUES (?,?,?,CASE WHEN ? THEN now() END,?,?,?)", tenantId, patientId, type, withdrawn, version, lang, channel));
    }

    @Test
    void everyPurposeIsDefaultDenyThenGrantThenWithdrawalDecides() {
        SeededTenant t = seedTenant();
        UUID p = patient(t.id());
        for (Purpose purpose : Purpose.values()) {
            assertThat(inTenantTx(t.id(), () -> registry.isGranted(p, purpose))).as(purpose + " default").isFalse();
        }
        consent(t.id(), p, "marketing", false, null, null, null);
        assertThat(inTenantTx(t.id(), () -> registry.isGranted(p, Purpose.MARKETING))).isTrue();
        assertThat(inTenantTx(t.id(), () -> registry.isGranted(p, Purpose.MESSAGING))).as("marketing is not messaging").isFalse();
        consent(t.id(), p, "marketing", true, null, null, null);
        assertThat(inTenantTx(t.id(), () -> registry.isGranted(p, Purpose.MARKETING))).as("newer withdrawal wins").isFalse();
    }

    @Test
    void consentJoinsExactlyOneNoticeByTenantVersionLanguage() {
        SeededTenant t = seedTenant();
        UUID p = patient(t.id());
        notice(t.id(), "v1", "en", "2026-01-01T00:00:00Z");
        notice(t.id(), "v1", "ar", "2026-01-01T00:00:00Z");
        consent(t.id(), p, "data_processing", false, "v1", "en", "DESK");
        Integer joined = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM consents c JOIN privacy_notices n " +
                "ON n.tenant_id = c.tenant_id AND n.version = c.notice_version AND n.language = c.language WHERE c.patient_id = ?", Integer.class, p));
        assertThat(joined).isEqualTo(1);

        assertThatThrownBy(() -> consent(t.id(), p, "messaging", false, "v9", "en", "DESK")).hasMessageContaining("consents_notice_fk");
        assertThatThrownBy(() -> consent(t.id(), p, "messaging", false, "v1", null, "DESK")).hasMessageContaining("consents_notice_pair_check");
        assertThatThrownBy(() -> notice(t.id(), "v1", "en", "2026-02-01T00:00:00Z")).hasMessageContaining("privacy_notices_tenant_id_version_language_key");
    }

    @Test
    void channelListAndNewPurposeAreChecked_legacyRowsWithoutNoticeStillInsert() {
        SeededTenant t = seedTenant();
        UUID p = patient(t.id());
        for (String ch : new String[]{"DESK", "WHATSAPP", "ASSISTED", "PORTAL"}) {
            consent(t.id(), p, "messaging", false, null, null, ch);
        }
        assertThatThrownBy(() -> consent(t.id(), p, "messaging", false, null, null, "FAX")).hasMessageContaining("consents_channel_check");
        assertThatThrownBy(() -> consent(t.id(), p, "newsletter", false, null, null, null)).hasMessageContaining("consents_consent_type_check");
        consent(t.id(), p, "data_processing", false, null, null, null); // no notice: legacy shape
    }

    @Test
    void publishedNoticeCannotBeEdited_andTenantsDoNotSeeEachOther() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        UUID pb = patient(b.id());
        notice(a.id(), "v1", "en", "2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> inTenantTx(a.id(), () -> jdbc.update("UPDATE privacy_notices SET body_md = 'changed' WHERE tenant_id = ?", a.id())))
                .hasMessageContaining("immutable");
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM privacy_notices", Integer.class))).isZero();
        assertThat(inTenantTx(b.id(), () -> registry.currentNotice("en"))).isEmpty();
        assertThatThrownBy(() -> consent(b.id(), pb, "messaging", false, "v1", "en", "DESK")).hasMessageContaining("consents_notice_fk");
        assertThatThrownBy(() -> inTenantTx(b.id(), () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash) " +
                "VALUES (?,'v1','en','k','b','h')", a.id()))).isInstanceOf(Exception.class); // WITH CHECK refuses a foreign tenant_id
    }

    @Test
    void currentNoticeIsTheNewestInForceVersionOfThatLanguage() {
        SeededTenant t = seedTenant();
        notice(t.id(), "v1", "en", "2026-01-01T00:00:00Z");
        notice(t.id(), "v2", "en", "2026-06-01T00:00:00Z");
        notice(t.id(), "v3", "en", "2999-01-01T00:00:00Z"); // not yet in force
        assertThat(inTenantTx(t.id(), () -> registry.currentNotice("en")).orElseThrow().version()).isEqualTo("v2");
        assertThat(inTenantTx(t.id(), () -> registry.currentNotice("ar"))).isEmpty();
    }

    @Test
    void adrRecordsPurposesDefaultsMarketsAndThreats() throws Exception {
        String adr = Files.readString(Path.of("../docs/adr/0018-consent-purposes-and-notices.md"));
        for (String s : new String[]{"`data_processing`", "`messaging`", "`marketing`", "`guardian_access`", "**deny**",
                "DPDP Act 2023", "PDPL", "Federal Law 2/2019", "Law 13/2016",
                "Forged channel evidence", "Replayed OTP", "without the patient present", "Notice edited after capture"}) {
            assertThat(adr).as(s).contains(s);
        }
    }
}
