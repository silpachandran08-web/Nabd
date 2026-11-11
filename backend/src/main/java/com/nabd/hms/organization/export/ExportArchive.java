package com.nabd.hms.organization.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ADM-015: the export archive (zip). Every file is recorded in manifest.json with its row count, size and SHA-256, so the owner
 * can check the archive against the clinic's own counts. CSV is RFC 4180; XLSX is a minimal SpreadsheetML workbook (inline strings,
 * no dependency); NDJSON is one JSON object per line.
 * shortcut: the archive is built in memory (byte[]), as ObjectStorage.put takes bytes; switch to a multipart streaming upload
 * before a clinic's export (files included) approaches 2 GB.
 */
final class ExportArchive {

    record Table(String name, List<String> columns, List<List<Object>> rows) {
    }

    record Entry(String file, long rows, long bytes, String sha256) {
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ZipOutputStream zip = new ZipOutputStream(out);
    private final List<Entry> entries = new ArrayList<>();

    void csv(String path, Table t) {
        StringBuilder sb = new StringBuilder();
        line(sb, new ArrayList<>(t.columns()));
        for (List<Object> r : t.rows()) {
            line(sb, r);
        }
        add(path, sb.toString().getBytes(StandardCharsets.UTF_8), t.rows().size());
    }

    void xlsx(String path, List<Table> sheets) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream x = new ZipOutputStream(b)) {
            StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            long rows = 0;
            for (int i = 0; i < sheets.size(); i++) {
                Table t = sheets.get(i);
                int n = i + 1;
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(n).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                wb.append("<sheet name=\"").append(xml(t.name().length() > 31 ? t.name().substring(0, 31) : t.name())).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
                StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
                row(s, new ArrayList<>(t.columns()));
                for (List<Object> r : t.rows()) {
                    row(s, r);
                }
                s.append("</sheetData></worksheet>");
                put(x, "xl/worksheets/sheet" + n + ".xml", s.toString());
                rows += t.rows().size();
            }
            put(x, "[Content_Types].xml", types.append("</Types>").toString());
            put(x, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(x, "xl/workbook.xml", wb.append("</sheets></workbook>").toString());
            put(x, "xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
            x.finish();
            add(path, b.toByteArray(), rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void add(String path, byte[] data, long rows) {
        try {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(data);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        entries.add(new Entry(path, rows, data.length, sha256(data)));
    }

    List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Writes manifest.json last (it is not listed in itself) and returns the archive. */
    byte[] finish(String manifestJson) {
        try {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Object> manifestEntry(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", e.file());
        m.put("rows", e.rows());
        m.put("bytes", e.bytes());
        m.put("sha256", e.sha256());
        return m;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void line(StringBuilder sb, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = cells.get(i) == null ? "" : String.valueOf(cells.get(i));
            sb.append(v.matches("^[^,\"\r\n]*$") && !v.startsWith("=") && !v.startsWith("+") && !v.startsWith("@") ? v
                    : "\"" + (v.startsWith("=") || v.startsWith("+") || v.startsWith("@") ? "'" : "") + v.replace("\"", "\"\"") + "\""); // no formula injection
        }
        sb.append("\r\n");
    }

    private static void row(StringBuilder s, List<Object> cells) {
        s.append("<row>");
        for (Object c : cells) {
            s.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(c == null ? "" : String.valueOf(c))).append("</t></is></c>");
        }
        s.append("</row>");
    }

    private static void put(ZipOutputStream x, String name, String body) throws IOException {
        x.putNextEntry(new ZipEntry(name));
        x.write(body.getBytes(StandardCharsets.UTF_8));
        x.closeEntry();
    }

    private static String xml(String v) {
        StringBuilder b = new StringBuilder(v.length());
        for (char ch : v.toCharArray()) {
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        b.append(ch);
                    }
                }
            }
        }
        return b.toString();
    }
}
