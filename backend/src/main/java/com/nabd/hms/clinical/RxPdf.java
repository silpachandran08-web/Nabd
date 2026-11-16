package com.nabd.hms.clinical;

import com.nabd.hms.queue.Qr;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

/**
 * CLN-023: the signed prescription as a PDF, A4 or A5, with no library (same minimal writer as ConsentPdf / FIN-012's SimplePdf):
 * Helvetica text, the QR as vector squares, the doctor's signature image as one Flate RGB image. Deterministic: the same sheet
 * gives the same bytes (the golden-file test pins the SHA-256 of an IN and a GCC sheet).
 * shortcut: page text is WinAnsi (Latin-1) only, so Arabic or Indic names print as '?'; the layout is ready for an embedded Unicode
 * font with shaping and right-to-left lines (T-268) when GCC Arabic printing is built.
 */
final class RxPdf {

    /** Everything printed, already formatted. signature: image bytes (PNG/JPEG) or null. qrText: the verify link. */
    record Sheet(String clinicName, String clinicPhone, String doctorName, String registrationNo, String council, byte[] signature,
                 String patientName, String ageSex, String issuedOn, String validUntil, String allergies, List<Medicine> medicines,
                 String signedAt, String qrText, String reference) {
    }

    record Medicine(String name, String details, String instructions) {
    }

    private static final float[] A4 = {595.28f, 841.89f};
    private static final float[] A5 = {419.53f, 595.28f};

    private RxPdf() {
    }

    /** CLN-021, as the browser printout does it: generic-first (IN) prints the generic with the brand in brackets only if one was chosen;
     * otherwise (GCC, unchanged) the name as written, with the brand as the first detail. */
    static Medicine medicine(String drugName, String brandName, String details, String instructions, boolean genericFirst) {
        boolean brand = brandName != null && !brandName.isBlank();
        if (genericFirst) return new Medicine(brand ? drugName + " (" + brandName + ")" : drugName, details, instructions);
        return new Medicine(drugName, join("   ", brand ? "Brand: " + brandName : null, details), instructions);
    }

    static byte[] render(Sheet s, boolean a5) {
        float[] page = a5 ? A5 : A4;
        float w = page[0], h = page[1], margin = a5 ? 28 : 40, size = a5 ? 8.5f : 10f, lead = size * 1.35f;
        int cols = (int) ((w - 2 * margin) / (size * 0.5f));
        Layout l = new Layout(w, h, margin, size, lead, cols);

        l.text(s.clinicName(), size * 1.5f, true);
        if (s.clinicPhone() != null) l.text(s.clinicPhone(), size, false);
        l.rule();
        l.text(s.doctorName(), size * 1.15f, true);
        String reg = join("   ", s.registrationNo() == null ? null : "Reg. no: " + s.registrationNo(), s.council() == null ? null : "Council: " + s.council());
        if (!reg.isEmpty()) l.text(reg, size, false);
        l.rule();
        l.text("Patient: " + s.patientName() + "   Age/Sex: " + s.ageSex(), size, false);
        l.text("Issued: " + s.issuedOn() + "   Valid until: " + s.validUntil(), size, false);
        l.wrapped(s.allergies(), size, false, "");
        l.gap();
        l.text("Rx", size * 1.3f, true);
        int n = 1;
        for (Medicine m : s.medicines()) {
            l.wrapped(n++ + ". " + m.name(), size, true, "   ");
            if (!m.details().isEmpty()) l.wrapped("   " + m.details(), size, false, "   ");
            if (m.instructions() != null && !m.instructions().isBlank()) l.wrapped("   Instructions: " + m.instructions().strip(), size, false, "   ");
        }
        l.gap();
        l.footer(s, a5);
        return l.pdf(s.signature());
    }

    /** Text, rules and the footer, top to bottom, with a new page when a row would cross the bottom margin. */
    private static final class Layout {
        final float w, h, margin, size, lead;
        final int cols;
        final List<StringBuilder> pages = new ArrayList<>();
        float y;
        boolean image;

        Layout(float w, float h, float margin, float size, float lead, int cols) {
            this.w = w;
            this.h = h;
            this.margin = margin;
            this.size = size;
            this.lead = lead;
            this.cols = cols;
            newPage();
        }

        void newPage() {
            pages.add(new StringBuilder());
            y = h - margin;
        }

        StringBuilder cur() {
            return pages.get(pages.size() - 1);
        }

        void room(float need) {
            if (y - need < margin) newPage();
        }

        void text(String t, float sz, boolean bold) {
            room(sz * 1.35f);
            y -= sz * 1.35f;
            cur().append("BT /").append(bold ? "F2 " : "F1 ").append(n(sz)).append(" Tf ").append(n(margin)).append(' ').append(n(y)).append(" Td (")
                    .append(esc(t)).append(") Tj ET\n");
        }

        void wrapped(String t, float sz, boolean bold, String indent) {
            boolean first = true;
            for (String line : wrap(t, cols)) {
                text(first ? line : indent + line, sz, bold);
                first = false;
            }
        }

        void rule() {
            room(lead);
            y -= lead / 2;
            cur().append("0.5 w ").append(n(margin)).append(' ').append(n(y)).append(" m ").append(n(w - margin)).append(' ').append(n(y)).append(" l S\n");
        }

        void gap() {
            y -= lead / 2;
        }

        /** Signature (left) and QR (right) side by side, kept together on one page. */
        void footer(Sheet s, boolean a5) {
            float qr = a5 ? 84 : 110, sigW = a5 ? 120 : 160, sigH = a5 ? 40 : 54;
            room(qr + lead * 2);
            float top = y;
            if (s.signature() != null && readImage(s.signature()) != null) {
                image = true;
                cur().append("q ").append(n(sigW)).append(" 0 0 ").append(n(sigH)).append(' ').append(n(margin)).append(' ').append(n(top - sigH))
                        .append(" cm /Im1 Do Q\n");
            }
            y = top - sigH;
            text("Signed electronically by " + s.doctorName(), size * 0.9f, false);
            if (s.registrationNo() != null) text("Reg. no: " + s.registrationNo(), size * 0.9f, false);
            text("Signed at: " + s.signedAt(), size * 0.9f, false);
            boolean[][] m = Qr.encode(s.qrText());
            float cell = qr / (m.length + 8), ox = w - margin - qr, oy = top - qr;
            for (int r = 0; r < m.length; r++) {
                for (int c = 0; c < m.length; c++) {
                    if (m[r][c]) cur().append(n(ox + (c + 4) * cell)).append(' ').append(n(oy + qr - (r + 5) * cell)).append(' ')
                            .append(n(cell)).append(' ').append(n(cell)).append(" re f\n");
                }
            }
            float capY = Math.min(y, oy) - size;
            cur().append("BT /F1 ").append(n(size * 0.8f)).append(" Tf ").append(n(ox)).append(' ').append(n(capY)).append(" Td (")
                    .append(esc("Scan to verify. Ref " + s.reference())).append(") Tj ET\n");
            y = capY;
        }

        byte[] pdf(byte[] signature) {
            // 1 catalog, 2 pages, 3 Helvetica, 4 Helvetica-Bold, 5 signature image (or an empty stream), then (page, content) pairs
            List<byte[]> objs = new ArrayList<>();
            objs.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
            StringBuilder kids = new StringBuilder();
            for (int p = 0; p < pages.size(); p++) kids.append(6 + 2 * p).append(" 0 R ");
            objs.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>"));
            objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
            objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
            objs.add(image ? imageObject(readImage(signature)) : bytes("<< /Length 0 >>\nstream\n\nendstream"));
            for (int p = 0; p < pages.size(); p++) {
                objs.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(w) + " " + n(h) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>"
                        + (image ? " /XObject << /Im1 5 0 R >>" : "") + " >> /Contents " + (7 + 2 * p) + " 0 R >>"));
                byte[] body = bytes(pages.get(p).toString());
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
    }

    /** The uploaded signature, scaled into 600 x 200 px at most and flattened onto white. Null when the bytes are not an image. */
    private static BufferedImage readImage(byte[] data) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return null;
            double k = Math.min(1, Math.min(600.0 / src.getWidth(), 200.0 / src.getHeight()));
            int iw = Math.max(1, (int) Math.round(src.getWidth() * k)), ih = Math.max(1, (int) Math.round(src.getHeight() * k));
            BufferedImage rgb = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, iw, ih);
            g.drawImage(src, 0, 0, iw, ih, null);
            g.dispose();
            return rgb;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] imageObject(BufferedImage img) {
        int iw = img.getWidth(), ih = img.getHeight();
        byte[] raw = new byte[iw * ih * 3];
        for (int yy = 0, k = 0; yy < ih; yy++) {
            for (int xx = 0; xx < iw; xx++) {
                int p = img.getRGB(xx, yy);
                raw[k++] = (byte) (p >> 16);
                raw[k++] = (byte) (p >> 8);
                raw[k++] = (byte) p;
            }
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        byte[] data = z.toByteArray();
        return concat(bytes("<< /Type /XObject /Subtype /Image /Width " + iw + " /Height " + ih + " /ColorSpace /DeviceRGB /BitsPerComponent 8 "
                + "/Filter /FlateDecode /Length " + data.length + " >>\nstream\n"), data, bytes("\nendstream"));
    }

    static List<String> wrap(String text, int cols) {
        List<String> out = new ArrayList<>();
        String rest = text == null ? "" : text.strip();
        while (rest.length() > cols) {
            int cut = rest.lastIndexOf(' ', cols);
            if (cut <= 0) cut = cols;
            out.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        out.add(rest);
        return out;
    }

    static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!b.isEmpty()) b.append(sep);
            b.append(p);
        }
        return b.toString();
    }

    private static String esc(String s) {
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
