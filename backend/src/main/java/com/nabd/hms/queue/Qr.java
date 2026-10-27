package com.nabd.hms.queue;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ACC-033: the door-poster QR code, with no library (none is on the build path). Byte mode, error correction M, versions 1 to 10
 * (up to 213 bytes: a wa.me link fits), mask 0 (any mask is valid; the penalty search only helps marginal scanners).
 * shortcut: no mask search and no versions above 10, upgrade if a longer payload is ever needed.
 */
final class Qr {

    private static final int[] ECC_PER_BLOCK = {10, 16, 26, 18, 24, 16, 18, 22, 22, 26}; // level M, versions 1..10
    private static final int[] BLOCKS = {1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int[][] ALIGN = {{}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};

    private Qr() {
    }

    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int ver = 1;
        while (ver <= 10 && dataCodewords(ver) * 8 < 4 + (ver < 10 ? 8 : 16) + data.length * 8) ver++;
        if (ver > 10) throw new IllegalArgumentException("QR payload too long");
        int size = ver * 4 + 17;
        boolean[][] m = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctions(m, fn, ver);
        byte[] cw = interleave(addEcc(bits(data, ver), ver), ver);
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j, y = ((right + 1) & 2) == 0 ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < cw.length * 8) {
                        m[y][x] = ((cw[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (!fn[y][x] && (x + y) % 2 == 0) m[y][x] = !m[y][x]; // mask 0
        formatBits(m, fn, size);
        return m;
    }

    private static int rawCodewords(int v) {
        int n = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int a = v / 7 + 2;
            n -= (25 * a - 10) * a - 55;
            if (v >= 7) n -= 36;
        }
        return n / 8;
    }

    private static int dataCodewords(int v) {
        return rawCodewords(v) - ECC_PER_BLOCK[v - 1] * BLOCKS[v - 1];
    }

    private static byte[] bits(byte[] data, int ver) {
        List<Boolean> b = new ArrayList<>();
        append(b, 4, 4);
        append(b, data.length, ver < 10 ? 8 : 16);
        for (byte x : data) append(b, x & 0xFF, 8);
        int cap = dataCodewords(ver) * 8;
        for (int i = 0; i < 4 && b.size() < cap; i++) b.add(false);
        while (b.size() % 8 != 0) b.add(false);
        byte[] out = new byte[dataCodewords(ver)];
        for (int i = 0; i < b.size(); i++) if (b.get(i)) out[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        for (int i = b.size() / 8, pad = 0xEC; i < out.length; i++, pad ^= 0xEC ^ 0x11) out[i] = (byte) pad;
        return out;
    }

    private static void append(List<Boolean> b, int val, int len) {
        for (int i = len - 1; i >= 0; i--) b.add(((val >>> i) & 1) != 0);
    }

    private static byte[][] addEcc(byte[] data, int ver) {
        int blocks = BLOCKS[ver - 1], ecc = ECC_PER_BLOCK[ver - 1], raw = rawCodewords(ver);
        int shortLen = raw / blocks, numShort = blocks - raw % blocks;
        byte[][] out = new byte[blocks][];
        int[] gen = generator(ecc);
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < numShort ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] rem = remainder(dat, gen);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, len); // short blocks keep one empty slot so the interleave indexes line up
            System.arraycopy(rem, 0, block, shortLen + 1 - ecc, ecc);
            out[i] = block;
        }
        return out;
    }

    private static byte[] interleave(byte[][] blocks, int ver) {
        int n = blocks.length, raw = rawCodewords(ver), shortLen = raw / n, numShort = n - raw % n, ecc = ECC_PER_BLOCK[ver - 1];
        byte[] out = new byte[raw];
        int k = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < n; j++) {
                if (i != shortLen - ecc || j >= numShort) out[k++] = blocks[j][i];
            }
        }
        return out;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static int[] generator(int degree) {
        int[] c = new int[degree];
        c[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                c[j] = mul(c[j], root);
                if (j + 1 < degree) c[j] ^= c[j + 1];
            }
            root = mul(root, 2);
        }
        return c;
    }

    private static byte[] remainder(byte[] data, int[] gen) {
        int[] r = new int[gen.length];
        for (byte b : data) {
            int f = (b & 0xFF) ^ r[0];
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(gen[i], f);
        }
        byte[] out = new byte[r.length];
        for (int i = 0; i < r.length; i++) out[i] = (byte) r[i];
        return out;
    }

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFunctions(boolean[][] m, boolean[][] fn, int ver) {
        int size = m.length;
        for (int i = 0; i < size; i++) {
            set(m, fn, 6, i, i % 2 == 0);
            set(m, fn, i, 6, i % 2 == 0);
        }
        for (int[] c : new int[][]{{3, 3}, {size - 4, 3}, {3, size - 4}}) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = c[0] + dx, y = c[1] + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                    if (x >= 0 && x < size && y >= 0 && y < size) set(m, fn, x, y, d != 2 && d != 4);
                }
            }
        }
        int[] a = ALIGN[ver - 1];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a.length; j++) {
                if (i == 0 && j == 0 || i == 0 && j == a.length - 1 || i == a.length - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) set(m, fn, a[i] + dx, a[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        formatBits(m, fn, size);
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int p = size - 11 + i % 3, q = i / 3;
                set(m, fn, p, q, bit);
                set(m, fn, q, p, bit);
            }
        }
    }

    /** Level M (bits 00) with mask 0. */
    private static void formatBits(boolean[][] m, boolean[][] fn, int size) {
        int rem = 0;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = rem ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(m, fn, 8, i, bit(bits, i));
        set(m, fn, 8, 7, bit(bits, 6));
        set(m, fn, 8, 8, bit(bits, 7));
        set(m, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, fn, 8, size - 15 + i, bit(bits, i));
        set(m, fn, 8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    /** A black-on-white PNG with the 4-module quiet zone. */
    static byte[] png(boolean[][] m, int scale) {
        int n = m.length + 8;
        BufferedImage img = new BufferedImage(n * scale, n * scale, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < n * scale; y++) {
            for (int x = 0; x < n * scale; x++) {
                int mx = x / scale - 4, my = y / scale - 4;
                boolean dark = mx >= 0 && my >= 0 && mx < m.length && my < m.length && m[my][mx];
                img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One A4 page: the QR as vector squares and two lines of Helvetica (ASCII only). */
    static byte[] pdf(boolean[][] m, String title, String line) {
        double cell = 360.0 / (m.length + 8), ox = (595 - 360) / 2.0, oy = 420;
        StringBuilder c = new StringBuilder("BT /F1 28 Tf 70 760 Td (" + esc(title) + ") Tj ET\nBT /F1 18 Tf 70 730 Td (" + esc(line) + ") Tj ET\n");
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (m[y][x]) c.append(String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f %.2f re f\n", ox + (x + 4) * cell, oy + 360 - (y + 5) * cell, cell, cell));
            }
        }
        String[] objs = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + c.length() + " >>\nstream\n" + c + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder out = new StringBuilder("%PDF-1.4\n");
        int[] off = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            off[i] = out.length();
            out.append(i + 1).append(" 0 obj\n").append(objs[i]).append("\nendobj\n");
        }
        int xref = out.length();
        out.append("xref\n0 ").append(objs.length + 1).append("\n0000000000 65535 f \n");
        for (int o : off) out.append(String.format("%010d 00000 n \n", o));
        out.append("trailer\n<< /Size ").append(objs.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String esc(String s) {
        return s.replaceAll("[^\\x20-\\x7E]", "?").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
