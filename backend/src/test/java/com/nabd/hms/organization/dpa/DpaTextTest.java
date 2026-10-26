package com.nabd.hms.organization.dpa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-002 unit: market wording (fiduciary vs controller), sub-processor list, HTML escaping, PDF shape. */
class DpaTextTest {

    private static String text(String market) {
        return String.join("\n", DpaText.lines(market, "Clinic <One>"));
    }

    @Test
    void indiaUsesFiduciaryAndProcessor() {
        String t = text("IN");
        assertThat(t).contains("(the data fiduciary)").contains("(the data processor)").contains("Digital Personal Data Protection Act, 2023");
        assertThat(t).doesNotContain("(the controller)");
    }

    @Test
    void gulfMarketsUseControllerAndProcessor() {
        for (String m : List.of("SA", "AE", "QA")) {
            String t = text(m);
            assertThat(t).as(m).contains("(the controller)").contains("(the processor)").doesNotContain("fiduciary");
        }
        assertThat(text("AE")).contains("Federal Law 2/2019");
        assertThat(text("QA")).contains("Law 13/2016");
        assertThat(text("SA")).contains("PDPL");
    }

    @Test
    void listsPublishedSubProcessors() {
        assertThat(text("IN")).contains("- Render: API hosting").contains("- Neon: Database hosting").contains("Meta Platforms (WhatsApp Cloud API)");
    }

    @Test
    void htmlEscapesTheClinicName() {
        String html = DpaText.html(DpaText.lines("IN", "Clinic <One>"));
        assertThat(html).contains("Clinic &lt;One&gt;").doesNotContain("<One>").contains("<ul>").contains("<h2>");
    }

    @Test
    void pdfIsWellFormedAndPaginates() {
        byte[] small = SimplePdf.render(DpaText.lines("IN", "Clinic"));
        String s = new String(small, StandardCharsets.ISO_8859_1);
        assertThat(s).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 1");
        List<String> many = java.util.stream.IntStream.range(0, 130).mapToObj(i -> "line " + i + " (paren) \\ end").toList();
        assertThat(new String(SimplePdf.render(many), StandardCharsets.ISO_8859_1)).contains("/Count 3").contains("\\(paren\\)");
    }

    @Test
    void versionAndReacceptWindowComeFromTheResource() {
        assertThat(DpaText.version()).isEqualTo("2026-10-v1");
        assertThat(DpaText.reacceptDays()).isEqualTo(30);
    }
}
