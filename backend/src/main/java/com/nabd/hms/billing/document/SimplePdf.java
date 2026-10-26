package com.nabd.hms.billing.document;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FIN-012: the smallest PDF writer that does the job: monospace text lines (Courier, so a column count is a real width and
 * the 80 mm layout can promise 48 columns), uncompressed so the golden test can read the text back. Not a general PDF library.
 * shortcut: Latin-1 text only (other characters become '?'), no images or fonts embedding; upgrade when a clinic logo or
 * Arabic/Hindi invoice text is required (needs an embedded font).
 */
final class SimplePdf {

    private SimplePdf() {
    }

    /** One page when {@code singlePage} (thermal roll: the page grows with the text), else A4-style pages of fixed height. */
    static byte[] render(List<String> lines, float width, float height, float margin, float fontSize, boolean singlePage) {
        float leading = fontSize * 1.25f;
        int perPage = Math.max(1, (int) ((height - 2 * margin) / leading));
        List<List<String>> pages = new ArrayList<>();
        if (singlePage) {
            pages.add(lines);
            perPage = lines.size();
        } else {
            for (int i = 0; i < lines.size(); i += perPage) pages.add(lines.subList(i, Math.min(lines.size(), i + perPage)));
            if (pages.isEmpty()) pages.add(List.of());
        }
        float pageH = singlePage ? 2 * margin + Math.max(1, lines.size()) * leading : height;

        // object numbers: 1 catalog, 2 pages, 3 font, then (page, content) pairs
        List<byte[]> objs = new ArrayList<>();
        objs.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages.size(); p++) kids.append(4 + 2 * p).append(" 0 R ");
        objs.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>"));
        objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>"));
        for (int p = 0; p < pages.size(); p++) {
            int content = 5 + 2 * p;
            objs.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(width) + " " + n(pageH) + "] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + content + " 0 R >>"));
            StringBuilder s = new StringBuilder("BT /F1 " + n(fontSize) + " Tf " + n(leading) + " TL " + n(margin) + " " + n(pageH - margin - fontSize) + " Td\n");
            for (String line : pages.get(p)) s.append('(').append(escape(line)).append(") Tj T*\n");
            s.append("ET");
            byte[] body = bytes(s.toString());
            objs.add(concat(bytes("<< /Length " + body.length + " >>\nstream\n"), body, bytes("\nendstream")));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes("%PDF-1.4\n"));
        int[] offsets = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            offsets[i] = out.size();
            out.writeBytes(bytes((i + 1) + " 0 obj\n"));
            out.writeBytes(objs.get(i));
            out.writeBytes(bytes("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) x.append(String.format("%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(bytes(x.toString()));
        return out.toByteArray();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append(c);
            else b.append(c < 32 || c > 255 ? '?' : c);
        }
        return b.toString();
    }

    private static String n(float f) {
        return String.format(java.util.Locale.ROOT, "%.2f", f);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }
}
