package com.nabd.hms.shared.cache;

import com.nabd.hms.shared.cache.CacheKeyPolicy.CacheRefusedException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-052 / ADR-04: the cache key helper against the real db/classification.yaml. */
class CacheKeyPolicyTest {

    static CacheKeyPolicy policy;
    static final UUID TENANT = UUID.randomUUID();

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("../db/classification.yaml"))) {
            policy = CacheKeyPolicy.fromYaml(in);
        }
    }

    @Test
    void readsTiersFromTheClassificationFile() {
        assertThat(policy.tierOf("public.clinical_notes.diagnosis")).isEqualTo(DataTier.PHI);
        assertThat(policy.tierOf("patients.name")).isEqualTo(DataTier.PII);
        assertThat(policy.tierOf("public.invoices.total")).isEqualTo(DataTier.FINANCIAL);
        assertThat(policy.tierOf("public.tenants.timezone")).isEqualTo(DataTier.OPERATIONAL);
        assertThat(policy.tierOf(List.of("tenants.timezone", "vitals.pulse_bpm"))).isEqualTo(DataTier.PHI);
    }

    @Test
    void refusesPhiWithoutTenantKeyOrWithoutExplicitAllow() {
        List<String> phi = List.of("public.patient_allergies.substance");
        assertThatThrownBy(() -> policy.key("allergies", TENANT, phi, false, "p1"))
                .isInstanceOf(CacheRefusedException.class).hasMessageContaining("explicit allow");
        assertThatThrownBy(() -> policy.key("allergies", null, phi, true, "p1"))
                .isInstanceOf(CacheRefusedException.class).hasMessageContaining("tenant key");
        assertThatThrownBy(() -> policy.key("allergies", null, phi, false, "p1")).isInstanceOf(CacheRefusedException.class);
        // a PHI column mixed into an otherwise operational value still makes the value PHI
        assertThatThrownBy(() -> policy.key("q", TENANT, List.of("queue_entries.status", "queue_entries.presenting_complaint"), false, "x"))
                .isInstanceOf(CacheRefusedException.class);
    }

    @Test
    void allowsPhiWithTenantKeyAndExplicitAllow() {
        assertThat(policy.key("allergies", TENANT, List.of("public.patient_allergies.substance"), true, "p1"))
                .isEqualTo("nabd:allergies:t:" + TENANT + ":p1");
    }

    @Test
    void unknownColumnIsTreatedAsPhi() {
        assertThat(policy.tierOf("public.not_a_table.secret")).isEqualTo(DataTier.PHI);
        assertThatThrownBy(() -> policy.key("x", TENANT, List.of("public.not_a_table.secret"), false, "1"))
                .isInstanceOf(CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("x", TENANT, List.of(), false, "1")).isInstanceOf(CacheRefusedException.class);
    }

    @Test
    void piiAndFinancialNeedTenantKeyOperationalMayBeGlobal() {
        assertThatThrownBy(() -> policy.key("staff", null, List.of("staff.email"), false, "s1")).isInstanceOf(CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("inv", null, List.of("invoices.total"), false, "i1")).isInstanceOf(CacheRefusedException.class);
        assertThat(policy.key("staff", TENANT, List.of("staff.email"), false, "s1")).isEqualTo("nabd:staff:t:" + TENANT + ":s1");
        assertThat(policy.key("rxdict", null, List.of("safety_dictionary_versions.version"), false, "IN", "v3"))
                .isEqualTo("nabd:rxdict:g:IN:v3");
    }

    @Test
    void refusesKeyPartsThatCouldForgeAnotherKey() {
        assertThatThrownBy(() -> policy.key("tz", TENANT, List.of("tenants.timezone"), false, "a:t:" + UUID.randomUUID()))
                .isInstanceOf(CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("tz", TENANT, List.of("tenants.timezone"), false, "a b")).isInstanceOf(CacheRefusedException.class);
        assertThatThrownBy(() -> policy.key("tz", TENANT, List.of("tenants.timezone"), false, "")).isInstanceOf(CacheRefusedException.class);
    }
}
