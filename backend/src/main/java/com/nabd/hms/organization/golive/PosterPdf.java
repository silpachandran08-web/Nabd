package com.nabd.hms.organization.golive;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One A4 page: clinic name, "Book your visit", the booking-link QR code as vector squares and the link as text. Helvetica only.
 * shortcut: Latin-1 text only (anything else prints as '?'); swap for the document renderer and an Arabic-capable font later.
 */
final class PosterPdf {

    private PosterPdf() {
    }

    static byte[] render(String clinicName, String url, boolean[][] qr) {
        StringBuilder c = new StringBuilder();
        c.append("BT /F1 34 Tf 60 760 Td (").append(escape("Book your visit")).append(") Tj ET\n");
        c.append("BT /F1 22 Tf 60 720 Td (").append(escape(clinicName)).append(") Tj ET\n");
        int quiet = 4;
        int n = qr.length + 2 * quiet;
        double module = 400.0 / n;
        double x0 = (595 - 400) / 2.0;
        double y0 = 250;
        c.append("1 1 1 rg ").append(num(x0)).append(' ').append(num(y0)).append(' ').append("400 400 re f\n0 0 0 rg\n");
        for (int y = 0; y < qr.length; y++) {
            for (int x = 0; x < qr.length; x++) {
                if (qr[y][x]) {
                    c.append(num(x0 + (x + quiet) * module)).append(' ').append(num(y0 + 400 - (y + quiet + 1) * module)).append(' ')
                            .append(num(module)).append(' ').append(num(module)).append(" re f\n");
                }
            }
        }
        c.append("BT /F1 14 Tf 60 200 Td (").append(escape("Scan with your phone camera, or open:")).append(") Tj ET\n");
        c.append("BT /F1 12 Tf 60 178 Td (").append(escape(url)).append(") Tj ET\n");
        byte[] content = c.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] off = new int[5];
        write(out, "%PDF-1.4\n");
        String[] objs = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Count 1 /Kids [4 0 R] >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents 5 0 R >>"};
        for (int i = 0; i < objs.length; i++) {
            off[i] = out.size();
            write(out, (i + 1) + " 0 obj\n" + objs[i] + "\nendobj\n");
        }
        off[4] = out.size();
        write(out, "5 0 obj\n<< /Length " + content.length + " >>\nstream\n");
        out.writeBytes(content);
        write(out, "\nendstream\nendobj\n");
        int xref = out.size();
        write(out, "xref\n0 6\n0000000000 65535 f \n");
        for (int o : off) {
            write(out, String.format(Locale.ROOT, "%010d 00000 n \n", o));
        }
        write(out, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            char t = ch > 255 || ch < 32 ? '?' : ch;
            if (t == '(' || t == ')' || t == '\\') {
                b.append('\\');
            }
            b.append(t);
        }
        return b.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
