package com.nabd.hms.shared.cache;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S12 PLT-052 AC3 / ADR-04): the cache key helper refuses PHI without a tenant key AND an explicit allow,
 * using the real db/classification.yaml. */
class S12QaPlt052CacheKeyPolicyTest {

    static CacheKeyPolicy policy;
    static final UUID T1 = UUID.randomUUID(), T2 = UUID.randomUUID();
    static final List<String> PHI = List.of("clinical_notes.diagnosis");
    static final List<String> PII = List.of("patients.name");
    static final List<String> FIN = List.of("invoices.total");
    static final List<String> OPS = List.of("queue_entries.status");

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("../db/classification.yaml"))) {
            policy = CacheKeyPolicy.fromYaml(in);
        }
    }

    @Test
    void phiWithTenantAndExplicitAllowGetsATenantScopedKey() {
        assertThat(policy.key("note", T1, PHI, true, "abc")).isEqualTo("nabd:note:t:" + T1 + ":abc");
    }

    @Test
    void phiWithTenantButNoAllowIsRefused() {
        assertThatThrownBy(() -> policy.key("note", T1, PHI, false, "abc")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class)
                .hasMessageContaining("PHI");
    }

    @Test
    void phiWithAllowButNoTenantIsRefused() {
        assertThatThrownBy(() -> policy.key("note", null, PHI, true, "abc")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    @Test
    void phiWithNeitherIsRefused() {
        assertThatThrownBy(() -> policy.key("note", null, PHI, false, "abc")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    @Test
    void oneHiddenPhiColumnAmongOperationalOnesMakesTheValuePhi() {
        List<String> mixed = List.of("queue_entries.status", "tenants.timezone", "clinical_notes.diagnosis");
        assertThat(policy.tierOf(mixed)).isEqualTo(DataTier.PHI);
        assertThatThrownBy(() -> policy.key("q", T1, mixed, false, "1")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    @Test
    void untaggedOrMisspelledColumnIsTreatedAsPhi() {
        assertThat(policy.tierOf("patients.qa_not_a_column")).isEqualTo(DataTier.PHI);
        assertThat(policy.tierOf("qa_no_table.x")).isEqualTo(DataTier.PHI);
        assertThatThrownBy(() -> policy.key("x", T1, List.of("patients.qa_not_a_column"), false, "1"))
                .isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    @Test
    void schemaQualifiedAndUpperCaseNamesResolveToTheSameTag() {
        assertThat(policy.tierOf("public.clinical_notes.diagnosis")).isEqualTo(DataTier.PHI);
        assertThat(policy.tierOf("CLINICAL_NOTES.DIAGNOSIS")).isEqualTo(DataTier.PHI);
        assertThat(policy.tierOf("public.queue_entries.status")).isEqualTo(DataTier.OPERATIONAL);
    }

    @Test
    void piiAndFinancialNeedATenantKeyButNoAllow() {
        assertThat(policy.key("p", T1, PII, false, "1")).startsWith("nabd:p:t:" + T1);
        assertThat(policy.key("f", T1, FIN, false, "1")).startsWith("nabd:f:t:" + T1);
        assertThatThrownBy(() -> policy.key("p", null, PII, false, "1")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("f", null, FIN, true, "1")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    @Test
    void onlyOperationalMayUseAGlobalKey() {
        assertThat(policy.key("cfg", null, OPS, false, "1")).isEqualTo("nabd:cfg:g:1");
    }

    @Test
    void twoTenantsNeverShareAPhiKey() {
        assertThat(policy.key("note", T1, PHI, true, "abc")).isNotEqualTo(policy.key("note", T2, PHI, true, "abc"));
    }

    @Test
    void keyPartsCannotForgeAnotherTenantsKey() {
        assertThatThrownBy(() -> policy.key("note", T1, PHI, true, "abc:t:" + T2)).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("no te", T1, OPS, false, "1")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("note", T1, OPS, false, "")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    @Test
    void undeclaredSourceColumnsAreRefused() {
        assertThatThrownBy(() -> policy.key("x", T1, List.of(), true, "1")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("x", T1, null, true, "1")).isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }
}
