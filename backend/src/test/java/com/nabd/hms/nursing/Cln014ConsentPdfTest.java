package com.nabd.hms.nursing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLN-014: render per language (no Spring): deterministic bytes, exact form text attached, signatures as vector strokes. */
class Cln014ConsentPdfTest {

    // language names only: the form wording itself is clinic data
    private static final Map<String, String> SAMPLE = Map.of("en", "English", "hi", "हिन्दी", "kn", "ಕನ್ನಡ", "te", "తెలుగు", "ar", "العربية");

    @Test
    void everyLanguageRendersTheSameBytesTwiceAndCarriesTheExactFormText() {
        for (var e : SAMPLE.entrySet()) {
            String body = "<p>" + e.getValue() + " &amp; form</p>";
            List<List<float[]>> strokes = ConsentSignature.parse("<svg><path d=\"M10 10 L50 60 L590 190\"/></svg>");
            List<String> lines = ConsentPdf.wrap(ConsentPdf.plainText(body), 90);
            byte[] a = ConsentPdf.render(lines, List.of(new ConsentPdf.Signature("Signature", strokes)), "consent-form.html", body.getBytes(StandardCharsets.UTF_8));
            byte[] b = ConsentPdf.render(lines, List.of(new ConsentPdf.Signature("Signature", strokes)), "consent-form.html", body.getBytes(StandardCharsets.UTF_8));
            assertThat(a).as(e.getKey()).isEqualTo(b);
            assertThat(ConsentService.sha256(a)).isEqualTo(ConsentService.sha256(b));
            String latin = new String(a, StandardCharsets.ISO_8859_1);
            assertThat(latin).as(e.getKey()).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/EmbeddedFiles").contains("(consent-form.html)")
                    .contains(" m\n").contains(" l\n"); // the signature strokes
            assertThat(indexOf(a, body.getBytes(StandardCharsets.UTF_8))).as(e.getKey() + " exact UTF-8 form text attached").isPositive();
            if (e.getKey().equals("en")) assertThat(latin).contains("(English & form) Tj");
            else assertThat(latin).as("page text is Latin-1 only (shortcut)").contains("? & form) Tj");
        }
    }

    @Test
    void signaturesKeepOnlyStrokeCoordinates() {
        assertThat(ConsentSignature.parse("<svg><script>x</script><path onclick=\"y\" d=\"M1 2 L3 4\"/></svg>")).hasSize(1);
        assertThat(ConsentSignature.toSvg(ConsentSignature.parse("<svg><path d=\"M1 2 L3 4\"/></svg>")))
                .isEqualTo("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 600 200\" width=\"600\" height=\"200\">"
                        + "<path fill=\"none\" stroke=\"#000\" stroke-width=\"2\" stroke-linecap=\"round\" d=\"M1.0 2.0 L3.0 4.0\"/></svg>");
        assertThat(ConsentSignature.parse("<svg><path d=\"M900 -5\"/></svg>").get(0).get(0)).containsExactly(600f, 0f); // clamped to the pad
        assertThat(ConsentSignature.parse("<svg></svg>")).isEmpty();
        assertThatThrownBy(() -> ConsentSignature.parse("<svg><path d=\"M1 2 C3 4 5 6 7 8\"/></svg>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsentSignature.parse("<svg><path d=\"L1 2\"/></svg>")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSignatureNeedsTenPointsSpanningFortyPadUnits() {
        assertThat(ConsentSignature.enoughInk(ConsentSignature.parse("<svg><path d=\"M0 0\"/></svg>"))).isFalse();
        assertThat(ConsentSignature.enoughInk(ConsentSignature.parse(
                "<svg><path d=\"M0 0 L1 0 L2 0 L3 0 L4 0 L5 0 L6 0 L7 0 L8 0 L9 0 L10 0\"/></svg>"))).as("11 points, 10 wide").isFalse();
        assertThat(ConsentSignature.enoughInk(ConsentSignature.parse(
                "<svg><path d=\"M0 0 L5 1 L10 2 L15 3 L20 4\"/><path d=\"M25 5 L30 6 L35 7 L40 8 L45 9\"/></svg>"))).as("10 points, 45 wide").isTrue();
        assertThat(ConsentSignature.enoughInk(java.util.List.of())).isFalse();
    }

    @Test
    void htmlBecomesPlainWrappedText() {
        assertThat(ConsentPdf.plainText("<p>Risks:</p><ul><li>redness</li><li>peeling</li></ul>")).isEqualTo("Risks:\n- redness\n- peeling");
        assertThat(ConsentPdf.wrap("aaa bbb ccc", 7)).containsExactly("aaa bbb", "ccc");
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
