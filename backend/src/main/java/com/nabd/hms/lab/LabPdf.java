package com.nabd.hms.lab;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PHL-043: a text PDF with one Code 128 symbol drawn as vector bars, with no library (the same minimal writer as FIN-012's
 * SimplePdf, which draws text only). The symbol sits at the top of the first page with its value printed under it; the lines
 * follow and continue on further pages. Uncompressed, so a test can read the text and the bars.
 * shortcut: page text is WinAnsi (Latin-1) only, anything else prints as '?'; swap for the document renderer when one exists.
 */
public final class LabPdf {

    /** Sizes in points. module: the width of one barcode module (the narrowest bar). */
    public record Page(float width, float height, float margin, float fontSize, float module, float barHeight) {
    }

    public static final Page A4 = new Page(595.28f, 841.89f, 40f, 10f, 1f, 36f);

    private LabPdf() {
    }

    /** @param barcode the value of the Code 128 symbol (ASCII 32 to 126), or null for none */
    public static byte[] render(Page pg, List<String> lines, String barcode) {
        float lead = pg.fontSize() * 1.4f;
        int cols = Math.max(10, (int) ((pg.width() - 2 * pg.margin()) / (pg.fontSize() * 0.5f)));
        List<String> wrapped = new ArrayList<>();
        for (String l : lines) {
            String t = l == null ? "" : l;
            while (t.length() > cols) {
                int cut = t.lastIndexOf(' ', cols);
                cut = cut < cols / 4 ? cols : cut;
                wrapped.add(t.substring(0, cut));
                t = t.substring(cut).stripLeading();
            }
            wrapped.add(t);
        }
        float symbol = barcode == null ? 0 : pg.barHeight() + 2 * lead;
        int perPage = Math.max(1, (int) ((pg.height() - 2 * pg.margin()) / lead));
        int firstPage = Math.max(1, (int) ((pg.height() - 2 * pg.margin() - symbol) / lead));
        List<String> pages = new ArrayList<>();
        for (int from = 0; from == 0 || from < wrapped.size(); ) {
            int n = pages.isEmpty() ? firstPage : perPage;
            StringBuilder s = new StringBuilder();
            float top = pg.height() - pg.margin();
            if (pages.isEmpty() && barcode != null) {
                String modules = Code128.modules(barcode);
                float x = pg.margin() + 10 * pg.module(); // quiet zone
                for (int i = 0; i < modules.length(); ) {
                    int j = i;
                    while (j < modules.length() && modules.charAt(j) == modules.charAt(i)) j++;
                    if (modules.charAt(i) == '1') {
                        s.append(n(x + i * pg.module())).append(' ').append(n(top - pg.barHeight())).append(' ')
                                .append(n((j - i) * pg.module())).append(' ').append(n(pg.barHeight())).append(" re\n");
                    }
                    i = j;
                }
                s.append("f\nBT /F1 ").append(n(pg.fontSize())).append(" Tf ").append(n(x)).append(' ').append(n(top - pg.barHeight() - lead))
                        .append(" Td (").append(escape(barcode)).append(") Tj ET\n");
                top -= symbol;
            }
            s.append("BT /F1 ").append(n(pg.fontSize())).append(" Tf ").append(n(lead)).append(" TL ").append(n(pg.margin())).append(' ')
                    .append(n(top - pg.fontSize())).append(" Td\n");
            for (String l : wrapped.subList(from, Math.min(wrapped.size(), from + n))) {
                s.append('(').append(escape(l)).append(") Tj T*\n");
            }
            s.append("ET");
            pages.add(s.toString());
            from += n;
        }
        // objects: 1 catalog, 2 pages, 3 font, then per page: page object and content stream
        List<byte[]> objs = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages.size(); p++) kids.append(4 + 2 * p).append(" 0 R ");
        objs.add(b("<< /Type /Catalog /Pages 2 0 R >>"));
        objs.add(b("<< /Type /Pages /Count " + pages.size() + " /Kids [" + kids + "] >>"));
        objs.add(b("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        for (int p = 0; p < pages.size(); p++) {
            byte[] content = b(pages.get(p));
            objs.add(b("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(pg.width()) + " " + n(pg.height()) + "] /Resources << /Font << /F1 3 0 R >> >> "
                    + "/Contents " + (5 + 2 * p) + " 0 R >>"));
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            stream.writeBytes(b("<< /Length " + content.length + " >>\nstream\n"));
            stream.writeBytes(content);
            stream.writeBytes(b("\nendstream"));
            objs.add(stream.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(b("%PDF-1.4\n"));
        int[] offsets = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            offsets[i] = out.size();
            out.writeBytes(b((i + 1) + " 0 obj\n"));
            out.writeBytes(objs.get(i));
            out.writeBytes(b("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) x.append(String.format("%010d 00000 n \n", o));
        out.writeBytes(b(x + "trailer\n<< /Size " + (objs.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
        return out.toByteArray();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append(c);
            else b.append(c < 32 || c > 255 ? '?' : c);
        }
        return b.toString();
    }

    private static String n(float f) {
        return String.format(java.util.Locale.ROOT, "%.2f", f);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
