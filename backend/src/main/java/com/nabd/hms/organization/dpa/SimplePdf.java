package com.nabd.hms.organization.dpa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text-only PDF (Helvetica, A4, no dependency). shortcut: Latin-1 only (anything else prints as '?') and no real layout;
 * swap for the document renderer once one exists, and for an Arabic-capable font before the GCC DPA is translated.
 */
final class SimplePdf {

    private static final int LINES_PER_PAGE = 52;
    private static final int WRAP = 92;

    private SimplePdf() {
    }

    static byte[] render(List<String> lines) {
        List<String> wrapped = new ArrayList<>();
        for (String l : lines) {
            String t = l.startsWith("## ") ? l.substring(3).toUpperCase() : l.startsWith("# ") ? l.substring(2).toUpperCase() : l;
            while (t.length() > WRAP) {
                int cut = t.lastIndexOf(' ', WRAP);
                cut = cut < 20 ? WRAP : cut;
                wrapped.add(t.substring(0, cut));
                t = t.substring(cut).stripLeading();
            }
            wrapped.add(t);
        }
        int pages = Math.max(1, (wrapped.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
        // objects: 1 catalog, 2 pages, 3 font, then per page: page object and content stream
        List<byte[]> objs = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages; p++) {
            kids.append(4 + 2 * p).append(" 0 R ");
        }
        objs.add(b("<< /Type /Catalog /Pages 2 0 R >>"));
        objs.add(b("<< /Type /Pages /Count " + pages + " /Kids [" + kids + "] >>"));
        objs.add(b("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        for (int p = 0; p < pages; p++) {
            StringBuilder s = new StringBuilder("BT /F1 10 Tf 14 TL 50 800 Td\n");
            for (String l : wrapped.subList(p * LINES_PER_PAGE, Math.min(wrapped.size(), (p + 1) * LINES_PER_PAGE))) {
                s.append('(').append(escape(l)).append(") '\n");
            }
            s.append("ET");
            byte[] content = s.toString().getBytes(StandardCharsets.ISO_8859_1);
            objs.add(b("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + (5 + 2 * p) + " 0 R >>"));
            objs.add(concat(b("<< /Length " + content.length + " >>\nstream\n"), content, b("\nendstream")));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        int[] offsets = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            offsets[i] = out.size();
            write(out, (i + 1) + " 0 obj\n");
            out.writeBytes(objs.get(i));
            write(out, "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) {
            x.append(String.format("%010d 00000 n \n", o));
        }
        write(out, x + "trailer\n<< /Size " + (objs.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                b.append('\\').append(c);
            } else {
                b.append(c < 32 || c > 255 ? '?' : c);
            }
        }
        return b.toString();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(b(s));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }
}
