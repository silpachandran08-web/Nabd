package com.nabd.hms.nursing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CLN-014: the signed consent PDF, same minimal writer as FIN-012's SimplePdf (A4, Courier text, uncompressed so tests can read
 * it back) plus the signatures drawn as vector strokes on the last page and the exact form text attached as an embedded file
 * ({@code consent-form.html}, UTF-8). Deterministic: the same input gives the same bytes, so the stored SHA-256 is reproducible.
 * shortcut: page text is Latin-1 only (Hindi/Kannada/Telugu/Arabic glyphs print as '?'); the attached form text is exact in every
 * language. Upgrade to an embedded Unicode font with shaping when a regional-script page render is required.
 */
final class ConsentPdf {

    record Signature(String label, List<List<float[]>> strokes) {
    }

    private static final float W = 595.3f;
    private static final float H = 841.9f;
    private static final float MARGIN = 40f;
    private static final float FONT = 9f;
    private static final float LEADING = FONT * 1.25f;
    private static final float SCALE = 0.5f; // a 600 x 200 pad box is drawn 300 x 100 pt

    private ConsentPdf() {
    }

    static byte[] render(List<String> lines, List<Signature> signatures, String attachmentName, byte[] attachment) {
        int perPage = (int) ((H - 2 * MARGIN) / LEADING);
        List<String> pagesText = new ArrayList<>();
        for (int i = 0; i < Math.max(1, lines.size()); i += perPage) {
            pagesText.add(text(lines.subList(Math.min(i, lines.size()), Math.min(lines.size(), i + perPage)), H - MARGIN - FONT));
        }
        StringBuilder sig = new StringBuilder();
        float y = H - MARGIN;
        for (Signature s : signatures) {
            y -= FONT;
            sig.append(text(List.of(s.label()), y));
            y -= 6 + 200 * SCALE;
            sig.append("0.5 w ").append(n(MARGIN)).append(' ').append(n(y)).append(' ').append(n(600 * SCALE)).append(' ').append(n(200 * SCALE)).append(" re S\n");
            sig.append("1.2 w 1 J 1 j\n");
            for (List<float[]> stroke : s.strokes()) {
                for (int i = 0; i < stroke.size(); i++) {
                    float px = MARGIN + stroke.get(i)[0] * SCALE;
                    float py = y + (200 - stroke.get(i)[1]) * SCALE;
                    sig.append(n(px)).append(' ').append(n(py)).append(i == 0 ? " m\n" : " l\n");
                }
                if (stroke.size() == 1) sig.append(n(MARGIN + stroke.get(0)[0] * SCALE + 0.5f)).append(' ').append(n(y + (200 - stroke.get(0)[1]) * SCALE)).append(" l\n");
                sig.append("S\n");
            }
            y -= 24;
        }
        pagesText.add(sig.toString());

        // 1 catalog, 2 pages, 3 font, 4 embedded file, 5 file spec, then (page, content) pairs
        List<byte[]> objs = new ArrayList<>();
        String name = "(" + escape(attachmentName) + ")";
        objs.add(bytes("<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles << /Names [" + name + " 5 0 R] >> >> >>"));
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pagesText.size(); p++) kids.append(6 + 2 * p).append(" 0 R ");
        objs.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pagesText.size() + " >>"));
        objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>"));
        objs.add(concat(bytes("<< /Type /EmbeddedFile /Subtype /text#2Fhtml /Length " + attachment.length + " >>\nstream\n"), attachment, bytes("\nendstream")));
        objs.add(bytes("<< /Type /Filespec /F " + name + " /UF " + name + " /EF << /F 4 0 R >> >>"));
        for (int p = 0; p < pagesText.size(); p++) {
            objs.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(W) + " " + n(H) + "] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + (7 + 2 * p) + " 0 R >>"));
            byte[] body = bytes(pagesText.get(p));
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
        for (int off : offsets) x.append(String.format(Locale.ROOT, "%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(bytes(x.toString()));
        return out.toByteArray();
    }

    /** Wraps text to {@code cols} columns, keeping blank lines. */
    static List<String> wrap(String text, int cols) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            String rest = para.strip();
            if (rest.isEmpty()) {
                out.add("");
                continue;
            }
            while (rest.length() > cols) {
                int cut = rest.lastIndexOf(' ', cols);
                if (cut <= 0) cut = cols;
                out.add(rest.substring(0, cut).stripTrailing());
                rest = rest.substring(cut).stripLeading();
            }
            out.add(rest);
        }
        return out;
    }

    /** The form's HTML as plain text: block ends become line breaks, tags are dropped, the common entities decoded. */
    static String plainText(String html) {
        String t = html.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>|</div>", "\n").replaceAll("(?i)<li[^>]*>", "- ").replaceAll("<[^>]*>", "");
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String text(List<String> lines, float top) {
        StringBuilder s = new StringBuilder("BT /F1 " + n(FONT) + " Tf " + n(LEADING) + " TL " + n(MARGIN) + " " + n(top) + " Td\n");
        for (String line : lines) s.append('(').append(escape(line)).append(") Tj T*\n");
        return s.append("ET\n").toString();
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
        return String.format(Locale.ROOT, "%.2f", f);
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
