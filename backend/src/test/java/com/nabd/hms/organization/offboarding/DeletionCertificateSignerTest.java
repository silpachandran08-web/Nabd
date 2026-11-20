package com.nabd.hms.organization.offboarding;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADM-016: the certificate's canonical form (RFC 8785) and its ES256 signature round trip. */
class DeletionCertificateSignerTest {

    private static Map<String, Object> doc() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "nabd.deletion-certificate.v1");
        d.put("rowCounts", Map.of("patients", 3L, "audit_log", 12));
        d.put("branches", List.of(Map.of("name", "Clinic \"A\"\n", "id", "x")));
        d.put("exportWaived", true);
        d.put("brandId", null);
        return d;
    }

    @Test
    void canonicalFormSortsMembers_escapesStrings_andRefusesFractions() {
        assertThat(DeletionCertificateSigner.canonical(doc())).isEqualTo(
                "{\"branches\":[{\"id\":\"x\",\"name\":\"Clinic \\\"A\\\"\\n\"}],\"brandId\":null,\"exportWaived\":true,"
                        + "\"rowCounts\":{\"audit_log\":12,\"patients\":3},\"type\":\"nabd.deletion-certificate.v1\"}");
        assertThat(DeletionCertificateSigner.canonical(Map.of("a", "\u0001é"))).isEqualTo("{\"a\":\"\\u0001é\"}");
        assertThatThrownBy(() -> DeletionCertificateSigner.canonical(Map.of("a", 1.5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSignedCertificateVerifies_andAnyChangeBreaksIt() {
        DeletionCertificateSigner signer = new DeletionCertificateSigner(new MockEnvironment());
        DeletionCertificateSigner.Signed s = signer.sign(doc());
        assertThat(s.sha256()).matches("[0-9a-f]{64}");
        assertThat(signer.verify(s.kid(), doc(), s.signature())).isTrue();

        Map<String, Object> changed = doc();
        changed.put("exportWaived", false);
        assertThat(signer.verify(s.kid(), changed, s.signature())).isFalse();
        assertThat(signer.verify("other-kid", doc(), s.signature())).isFalse();
        assertThat(signer.verify(s.kid(), doc(), "not base64 !")).isFalse();
        assertThat(signer.publicJwks().toString()).doesNotContain("\"d\"").doesNotContain("d=");
    }

    @Test
    void underProdNothingIsSignedWithoutAConfiguredKey() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new DeletionCertificateSigner(prod).sign(doc())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELETION_CERT_KEYS_FILE");
    }
}
