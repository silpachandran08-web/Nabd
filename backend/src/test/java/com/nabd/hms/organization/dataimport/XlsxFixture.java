package com.nabd.hms.organization.dataimport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds a minimal real .xlsx (zip of XML) with no office library: shared strings for text, plain numbers for digits. */
final class XlsxFixture {

    private XlsxFixture() {
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Cells that are all digits become numeric cells (like a typed phone number in Excel); the rest shared strings. */
    static byte[] build(List<String[]> rows) throws Exception {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        List<String> shared = new java.util.ArrayList<>();
        int r = 1;
        for (String[] row : rows) {
            sheet.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.length; c++) {
                String ref = (char) ('A' + c) + "" + r;
                String v = row[c];
                if (v.isEmpty()) continue; // a blank cell is simply absent in xlsx
                if (v.matches("\\d{1,15}")) sheet.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                else { shared.add(v); sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size() - 1).append("</v></c>"); }
            }
            sheet.append("</row>");
            r++;
        }
        sheet.append("</sheetData></worksheet>");
        StringBuilder ss = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        for (String s : shared) ss.append("<si><t>").append(esc(s)).append("</t></si>");
        ss.append("</sst>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            // sharedStrings after the sheet, as Excel often writes it
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
            put(z, "xl/worksheets/sheet1.xml", sheet.toString());
            put(z, "xl/sharedStrings.xml", ss.toString());
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String body) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
