package com.nabd.hms.clinical;

import com.nabd.hms.queue.Qr;
import com.nabd.hms.shared.rxsign.RxQr;
import com.nabd.hms.shared.rxsign.RxSigningKeys;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-023 without Spring or a database (QR decoding of versions 8 and 11-14 was checked once with Chrome's BarcodeDetector, see the
 * story report): golden files of the Rx PDF layout (IN generic-first A4, GCC A5), the signature image, and the
 * crypto a verifier with no network does (the published JWKS alone checks the token; a tampered token fails). */
class Cln023RxPdfTest {

    // Golden SHA-256 of the two sheets below. A layout change must update these on purpose (and be looked at in a PDF viewer).
    static final String GOLDEN_IN_A4 = "efc1843b386f674c28c9b9ffba79fc04cef73c390af31712ba0120f7a526d46d";
    static final String GOLDEN_GCC_A5 = "c4126ab175569938e485d51e1ab46b1228bb645f38414fa6ce442ec0d7632499";

    static final String TOKEN = "NRX1.ERERERERQRGBERERERERE.MzMzMzMzQzODMzMzMzMzMzMw.1794825000.1797417000.YTFiMmMzZDQ.S01DLTEyMzQ1."
            + "c2lnbmF0dXJlLXBsYWNlaG9sZGVyLWZvci10aGUtZ29sZGVuLWZpbGUtb25seS1ub3QtYS1yZWFsLXNpZ25hdHVyZS1zdHJpbmctMDEyMzQ1Njc4OQ";

    static RxPdf.Sheet in(byte[] signature) {
        return new RxPdf.Sheet("Test Clinic Bengaluru", "+91 80 4000 0000", "Dr Asha Rao", "KMC-12345", "Karnataka Medical Council", signature,
                "Ravi Kumar", "34 y / male", "16 Nov 2026", "16 Dec 2026", "Allergies at signing: no known allergies (documented).",
                List.of(RxPdf.medicine("Paracetamol 500 mg tablet", "Calpol", "Route: oral   Dose: 1 tab   Frequency: TDS   Duration: 3 days   Qty: 9 tablets",
                                "After food", true),
                        RxPdf.medicine("Amoxicillin 500 mg capsule", null, "Dose: 1 cap   Frequency: BD   Duration: 5 days   Qty: 10 capsules", null, true)),
                "16 Nov 2026, 10:30", "http://localhost:3810/rx/" + TOKEN, "0123456789ab");
    }

    static RxPdf.Sheet gcc() {
        return new RxPdf.Sheet("Test Clinic Riyadh", null, "Dr Omar Saleh", "SCFHS-998877", null, null,
                "Fahad Ali", "41 y / male", "16 Nov 2026", "no expiry set", "Allergies at signing: Penicillin (severe).",
                List.of(RxPdf.medicine("Paracetamol 500 mg tablet", "Panadol", "Dose: 1 tab   Frequency: TDS", null, false)),
                "16 Nov 2026, 10:30", "https://patient.example.sa/rx/" + TOKEN, "0123456789ab");
    }

    static String sha(byte[] b) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void goldenFilesForTheIndianA4AndTheGccA5Layout() throws Exception {
        byte[] a4 = RxPdf.render(in(null), false);
        assertThat(RxPdf.render(in(null), false)).as("deterministic").isEqualTo(a4);
        assertThat(sha(a4)).isEqualTo(GOLDEN_IN_A4);
        String text = new String(a4, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-1.4").contains("/MediaBox [0 0 595.28 841.89]")
                .contains("(Test Clinic Bengaluru)").contains("(Dr Asha Rao)").contains("(Reg. no: KMC-12345   Council: Karnataka Medical Council)")
                .contains("(Patient: Ravi Kumar   Age/Sex: 34 y / male)").contains("(Issued: 16 Nov 2026   Valid until: 16 Dec 2026)")
                .contains("(1. Paracetamol 500 mg tablet \\(Calpol\\))").contains("(2. Amoxicillin 500 mg capsule)")
                .contains("(Scan to verify. Ref 0123456789ab)").contains(" re f\n")
                .doesNotContain("Diagnosis");

        byte[] a5 = RxPdf.render(gcc(), true);
        assertThat(sha(a5)).isEqualTo(GOLDEN_GCC_A5);
        String g = new String(a5, StandardCharsets.ISO_8859_1);
        assertThat(g).contains("/MediaBox [0 0 419.53 595.28]").contains("(1. Paracetamol 500 mg tablet)").contains("Brand: Panadol")
                .contains("Valid until: no expiry set").doesNotContain("Council:");
    }

    @Test
    void theUploadedSignatureIsEmbeddedAsAnImageAndAnUnreadableOneIsLeftOut() throws Exception {
        BufferedImage img = new BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB);
        for (int x = 20; x < 280; x++) img.setRGB(x, 50 + (int) (20 * Math.sin(x / 15.0)), 0xFF000000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String with = new String(RxPdf.render(in(png.toByteArray()), false), StandardCharsets.ISO_8859_1);
        assertThat(with).contains("/Subtype /Image /Width 300 /Height 100").contains("/XObject << /Im1 5 0 R >>").contains("/Im1 Do");
        String junk = new String(RxPdf.render(in("not an image".getBytes(StandardCharsets.UTF_8)), false), StandardCharsets.ISO_8859_1);
        assertThat(junk).doesNotContain("/Im1 Do").doesNotContain("/Subtype /Image");
    }

    @Test
    void theVerifyLinkWithItsTokenFitsTheQrAndAManyLineRxFlowsOntoASecondPage() {
        String url = "https://patient-web.nabd.example.com/rx/" + TOKEN + "x".repeat(40);
        assertThat(url.length()).isGreaterThan(213);
        assertThat(Qr.encode(url).length).isLessThanOrEqualTo(73);
        List<RxPdf.Medicine> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(RxPdf.medicine("Drug " + i, null, "Dose: 1 tab   Frequency: OD", null, true));
        RxPdf.Sheet s = in(null);
        String text = new String(RxPdf.render(new RxPdf.Sheet(s.clinicName(), s.clinicPhone(), s.doctorName(), s.registrationNo(), s.council(), null,
                s.patientName(), s.ageSex(), s.issuedOn(), s.validUntil(), s.allergies(), many, s.signedAt(), s.qrText(), s.reference()), true), StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("/Count 1 ").contains("(40. Drug 39)").contains("Scan to verify");
    }

    // ---------------- crypto: offline verification with the published JWKS only ----------------

    @Test
    void aVerifierWithOnlyThePublishedJwksChecksTheTokenAndATamperedOneFails() throws Exception {
        ECKey key = RxSigningKeys.ephemeral();
        RxSigningKeys keys = new RxSigningKeys(new MockEnvironment().withProperty("app.rx.keys-json", new JWKSet(key).toString(false)));
        UUID tenant = UUID.randomUUID(), rx = UUID.randomUUID();
        String token = RxQr.encode(new RxQr.Claims(tenant, rx, Instant.ofEpochSecond(1_794_825_000L), null, keys.activeKid(), "KMC-12345"), keys);

        // what the verifier has: the JSON of GET /v1/public/rx/keys, no private part
        Map<String, Object> published = keys.publicJwks();
        assertThat(published.toString()).doesNotContain("\"d\"").doesNotContain(", d=");
        JWKSet jwks = JWKSet.parse(published);
        assertThat(verifyOffline(jwks, token)).isTrue();
        assertThat(verifyOffline(jwks, "http://localhost:3810/rx/" + token)).as("scanned as the link").isTrue();

        String[] f = token.split("\\.", -1);
        f[4] = "1894825000"; // validity stretched by the holder
        assertThat(verifyOffline(jwks, String.join(".", f))).isFalse();
        assertThat(RxQr.decode(String.join(".", f), keys).orElseThrow().signatureValid()).isFalse();
        assertThat(verifyOffline(JWKSet.parse(Map.of("keys", List.of(RxSigningKeys.ephemeral().toPublicJWK().toJSONObject()))), token))
                .as("another key set").isFalse();
        assertThat(RxQr.decode("http://localhost:3810/rx/" + token, keys).orElseThrow().claims().prescriptionId()).isEqualTo(rx);
    }

    /** The whole offline check: split, find the kid in the JWKS, ES256 (raw r||s) over the text before the last dot. */
    static boolean verifyOffline(JWKSet jwks, String scanned) throws Exception {
        String t = scanned.substring(scanned.lastIndexOf('/') + 1);
        String[] f = t.split("\\.", -1);
        if (f.length != 8 || !"NRX1".equals(f[0])) return false;
        String kid = new String(Base64.getUrlDecoder().decode(f[5]), StandardCharsets.UTF_8);
        if (!(jwks.getKeyByKeyId(kid) instanceof ECKey k)) return false;
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(k.toECPublicKey());
        s.update(t.substring(0, t.lastIndexOf('.')).getBytes(StandardCharsets.UTF_8));
        return s.verify(Base64.getUrlDecoder().decode(f[7]));
    }
}
