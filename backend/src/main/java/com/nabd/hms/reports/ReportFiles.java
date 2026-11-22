package com.nabd.hms.reports;

import com.nabd.hms.organization.dpa.SimplePdf;
import com.nabd.hms.reports.dto.ReportFrame;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ADM-047: CSV, XLSX and PDF of one report frame, no dependency. XLSX is the smallest workbook Excel opens (one sheet, inline
 * strings, no styles); PDF is the text-only writer the DPA export already uses. shortcut: no number formats or column widths in
 * the workbook and Latin-1 only in the PDF; upgrade with a real library when a styled export or Arabic/Hindi PDF is required.
 */
final class ReportFiles {

    private ReportFiles() {
    }

    static String cell(Object v) {
        return v == null ? "" : v instanceof java.math.BigDecimal b ? b.toPlainString() : v.toString();
    }

    /** UTF-8 with BOM (Excel reads Arabic and Hindi names), RFC 4180; text a spreadsheet would run as a formula gets a leading apostrophe. */
    static byte[] csv(ReportFrame f) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", f.columns().stream().map(c -> csvField(c.label())).toList())).append("\r\n");
        for (Map<String, Object> r : f.rows()) {
            sb.append(String.join(",", f.columns().stream().map(c -> csvField(cell(r.get(c.key())))).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String csvField(String v) {
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static byte[] xlsx(ReportFrame f) {
        List<List<String[]>> rows = new ArrayList<>(); // each cell: {type n|s, text}
        rows.add(java.util.Collections.singletonList(new String[]{"s", f.title()}));
        rows.add(java.util.Collections.singletonList(new String[]{"s", f.from() + " to " + f.to()}));
        rows.add(java.util.Collections.singletonList(new String[]{"s", f.rule()}));
        rows.add(List.of());
        rows.add(f.columns().stream().map(c -> new String[]{"s", c.label()}).toList());
        for (Map<String, Object> r : f.rows()) {
            rows.add(f.columns().stream().map(c -> {
                boolean num = ("money".equals(c.type()) || "number".equals(c.type())) && r.get(c.key()) instanceof Number;
                return new String[]{num ? "n" : "s", cell(r.get(c.key()))};
            }).toList());
        }
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int i = 0; i < rows.size(); i++) {
            sheet.append("<row r=\"").append(i + 1).append("\">");
            List<String[]> cells = rows.get(i);
            for (int j = 0; j < cells.size(); j++) {
                String ref = colName(j) + (i + 1);
                String[] c = cells.get(j);
                if (c[1].isEmpty()) continue;
                if ("n".equals(c[0])) sheet.append("<c r=\"").append(ref).append("\"><v>").append(xml(c[1])).append("</v></c>");
                else sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(c[1])).append("</t></is></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            put(z, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] pdf(ReportFrame f) {
        List<String> lines = new ArrayList<>();
        lines.add("# " + f.title());
        lines.add(f.from() + " to " + f.to());
        lines.add(f.rule());
        if (f.masked()) lines.add("Patient names are masked.");
        lines.add("");
        lines.add(String.join(" | ", f.columns().stream().map(ReportFrame.Column::label).toList()));
        for (Map<String, Object> r : f.rows()) lines.add(String.join(" | ", f.columns().stream().map(c -> cell(r.get(c.key()))).toList()));
        return SimplePdf.render(lines);
    }

    private static void put(ZipOutputStream z, String name, String body) throws java.io.IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static String colName(int i) {
        StringBuilder s = new StringBuilder();
        for (int n = i; n >= 0; n = n / 26 - 1) s.insert(0, (char) ('A' + n % 26));
        return s.toString();
    }

    /** Escapes markup and drops the control characters XML 1.0 forbids. */
    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
