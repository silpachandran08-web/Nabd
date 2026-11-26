package com.nabd.hms.lab;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-043: the lab module's PDF writer: text lines and one Code 128 symbol as vector bars. */
class LabPdfTest {

    static String raw(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    /** The printed text lines of a LabPdf file. */
    static List<String> lines(byte[] pdf) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("^(?:BT .* Td )?\\((.*)\\) Tj( T\\*| ET)$", Pattern.MULTILINE).matcher(raw(pdf));
        while (m.find()) out.add(m.group(1).replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\"));
        return out;
    }

    /** The bars drawn, as module runs read back from the rectangles: x and width in modules from the first bar. */
    static String barsOf(byte[] pdf, float module) {
        Matcher m = Pattern.compile("^([0-9.]+) [0-9.]+ ([0-9.]+) [0-9.]+ re$", Pattern.MULTILINE).matcher(raw(pdf));
        StringBuilder b = new StringBuilder();
        float origin = -1;
        while (m.find()) {
            float x = Float.parseFloat(m.group(1));
            if (origin < 0) origin = x;
            int at = Math.round((x - origin) / module), w = Math.round(Float.parseFloat(m.group(2)) / module);
            b.append("0".repeat(at - b.length())).append("1".repeat(w));
        }
        return b.toString();
    }

    @Test
    void aSheetCarriesItsLinesAndTheBarcodeOfTheReference() {
        byte[] pdf = LabPdf.render(LabPdf.A4, List.of("Clinic (Main)", "Patient: Test Person", "1. CRP  C-reactive protein"), "LO-0A1B2C3D4E5F");
        String raw = raw(pdf);
        assertThat(raw).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 1 ");
        assertThat(lines(pdf)).containsExactly("LO-0A1B2C3D4E5F", "Clinic (Main)", "Patient: Test Person", "1. CRP  C-reactive protein");
        assertThat(barsOf(pdf, LabPdf.A4.module())).as("the bars are exactly the Code 128 symbol of the reference").isEqualTo(Code128.modules("LO-0A1B2C3D4E5F"));
        // startxref points at the xref table
        int xref = Integer.parseInt(raw.substring(raw.indexOf("startxref\n") + 10, raw.indexOf("\n%%EOF")));
        assertThat(raw.substring(xref)).startsWith("xref\n");
    }

    @Test
    void longContentWrapsAndContinuesOnFurtherPages_andASmallLabelFits() {
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 130; i++) many.add(i + ". TEST" + i);
        many.add("word ".repeat(60).trim());
        byte[] pdf = LabPdf.render(LabPdf.A4, many, "LO-1");
        assertThat(raw(pdf)).contains("/Count 3 ");
        List<String> printed = lines(pdf);
        assertThat(printed).contains("1. TEST1", "130. TEST130").hasSizeGreaterThan(132);
        assertThat(printed).allMatch(l -> l.length() <= 103, "wrapped to the page width");

        LabPdf.Page label = new LabPdf.Page(142f, 71f, 6f, 6f, 0.6f, 20f); // a 50 x 25 mm sample label
        byte[] small = LabPdf.render(label, List.of("Test Person", "EDTA"), "260123-0042");
        assertThat(raw(small)).contains("/MediaBox [0 0 142.00 71.00]");
        assertThat(barsOf(small, 0.6f)).isEqualTo(Code128.modules("260123-0042"));
        assertThat(lines(LabPdf.render(LabPdf.A4, List.of("only text"), null))).containsExactly("only text");
        assertThat(raw(LabPdf.render(LabPdf.A4, List.of("only text"), null))).doesNotContain(" re\n");
    }

    @Test
    void textOutsideLatin1PrintsAsAQuestionMarkAndNeverBreaksTheFile() {
        assertThat(lines(LabPdf.render(LabPdf.A4, List.of("आशा (x) \\ end"), null))).containsExactly("??? (x) \\ end");
    }
}
