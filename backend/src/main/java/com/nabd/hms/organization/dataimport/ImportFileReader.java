package com.nabd.hms.organization.dataimport;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ADM-012: CSV and XLSX into rows of strings (row 0 = header). No office library: an XLSX is a zip of XML, read with
 * StAX (DTDs and external entities off) and a hard cap on the unzipped size. shortcut: first worksheet only, cell values
 * as written (a date cell arrives as its Excel serial number, ImportMapper converts it); upgrade to POI if formulas,
 * several sheets or merged cells matter.
 */
final class ImportFileReader {

    static final int MAX_ROWS = 100_000;
    static final int MAX_COLS = 40;
    private static final long MAX_UNZIPPED = 150L * 1024 * 1024;

    private ImportFileReader() {
    }

    /** 'xlsx' (zip magic PK) or 'csv' (anything text); null when the bytes are neither. */
    static String sniff(byte[] data, String declared) {
        boolean zip = data.length > 3 && data[0] == 'P' && data[1] == 'K';
        if ("xlsx".equals(declared)) return zip ? "xlsx" : null;
        if ("csv".equals(declared)) {
            for (int i = 0, n = Math.min(data.length, 4096); i < n; i++) if (data[i] == 0) return null; // binary, not text
            return zip ? null : "csv";
        }
        return null;
    }

    static List<String[]> read(byte[] data, String format) throws IOException {
        return "xlsx".equals(format) ? xlsx(data) : csv(new String(data, StandardCharsets.UTF_8));
    }

    static List<String[]> csv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        int nl = text.indexOf('\n');
        String head = nl < 0 ? text : text.substring(0, nl);
        char sep = count(head, ';') > count(head, ',') ? ';' : count(head, '\t') > count(head, ',') ? '\t' : ',';
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); addRow(rows, row); }
        return rows;
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }

    private static void addRow(List<String[]> rows, List<String> row) {
        if (row.stream().allMatch(v -> v.isBlank())) return; // blank line
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("TOO_MANY_ROWS");
        rows.add(row.subList(0, Math.min(row.size(), MAX_COLS)).toArray(new String[0]));
    }

    static List<String[]> xlsx(byte[] data) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                if (!n.equals("xl/sharedStrings.xml") && !n.matches("xl/worksheets/sheet\\d+\\.xml")) continue;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int r; (r = zip.read(buf)) > 0; ) {
                    total += r;
                    if (total > MAX_UNZIPPED) throw new IllegalArgumentException("FILE_TOO_LARGE");
                    out.write(buf, 0, r);
                }
                parts.put(n, out.toByteArray());
            }
        }
        String sheet = parts.keySet().stream().filter(k -> k.startsWith("xl/worksheets/")).min(String::compareTo).orElse(null);
        if (sheet == null) throw new IllegalArgumentException("NO_SHEET");
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            return sheetRows(parts.get(sheet), shared);
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("BAD_XLSX");
        }
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader((InputStream) new ByteArrayInputStream(xml));
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        StringBuilder cur = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("si")) cur = new StringBuilder();
            else if (ev == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("t") && cur != null) cur.append(r.getElementText());
            else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("si")) { out.add(cur.toString()); cur = null; }
        }
        return out;
    }

    private static List<String[]> sheetRows(byte[] xml, List<String> shared) throws XMLStreamException {
        List<String[]> rows = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        String[] cur = null;
        String type = null;
        int col = 0;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> { cur = new String[MAX_COLS]; col = 0; }
                    case "c" -> {
                        type = r.getAttributeValue(null, "t");
                        col = columnIndex(r.getAttributeValue(null, "r"), col);
                    }
                    case "v", "t" -> {
                        if (cur == null || col >= MAX_COLS) break;
                        boolean isV = r.getLocalName().equals("v");
                        String v = r.getElementText();
                        if (isV && "s".equals(type)) v = shared.get(Integer.parseInt(v.trim()));
                        else if (isV && v.indexOf('E') >= 0 && (type == null || "n".equals(type))) v = new BigDecimal(v).toPlainString();
                        cur[col] = cur[col] == null ? v : cur[col] + v; // inline strings may come in runs
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("c")) col++;
                else if (r.getLocalName().equals("row") && cur != null) {
                    int last = cur.length - 1;
                    while (last >= 0 && (cur[last] == null || cur[last].isBlank())) last--;
                    if (last >= 0) {
                        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("TOO_MANY_ROWS");
                        String[] row = Arrays.copyOf(cur, last + 1);
                        for (int i = 0; i < row.length; i++) if (row[i] == null) row[i] = "";
                        rows.add(row);
                    }
                    cur = null;
                }
            }
        }
        return rows;
    }

    /** "C12" -> 2; falls back to the running column when the cell has no reference. */
    private static int columnIndex(String ref, int fallback) {
        if (ref == null) return fallback;
        int n = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        return n == 0 ? fallback : n - 1;
    }
}
