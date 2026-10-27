package com.nabd.hms.organization.golive;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR Code encoder for the booking-link poster: byte mode, error correction L, versions 1 to 5 (one Reed-Solomon block each,
 * so up to 106 bytes of text). shortcut: no other modes or versions; a longer link throws, move to a library if links grow.
 */
final class QrCode {

    private static final int[] DATA_CODEWORDS = {19, 34, 55, 80, 108}; // versions 1..5, level L
    private static final int[] ECC_CODEWORDS = {7, 10, 15, 20, 26};

    private QrCode() {
    }

    /** @return the module matrix [row][column], true = dark */
    static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int v = -1;
        for (int i = 0; i < DATA_CODEWORDS.length; i++) {
            if (bytes.length + 2 <= DATA_CODEWORDS[i] && (4 + 8 + bytes.length * 8) <= DATA_CODEWORDS[i] * 8) {
                v = i;
                break;
            }
        }
        if (v < 0) {
            throw new IllegalArgumentException("text too long for the poster QR code");
        }
        int dataLen = DATA_CODEWORDS[v];
        int size = 17 + 4 * (v + 1);

        StringBuilder bits = new StringBuilder("0100");
        append(bits, bytes.length, 8);
        for (byte b : bytes) {
            append(bits, b & 0xFF, 8);
        }
        bits.append("0".repeat(Math.min(4, dataLen * 8 - bits.length())));
        while (bits.length() % 8 != 0) {
            bits.append('0');
        }
        byte[] data = new byte[dataLen];
        for (int i = 0; i < bits.length() / 8; i++) {
            data[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        for (int i = bits.length() / 8, pad = 0xEC; i < dataLen; i++, pad ^= 0xEC ^ 0x11) {
            data[i] = (byte) pad;
        }
        byte[] all = new byte[dataLen + ECC_CODEWORDS[v]];
        System.arraycopy(data, 0, all, 0, dataLen);
        System.arraycopy(rsRemainder(data, ECC_CODEWORDS[v]), 0, all, dataLen, ECC_CODEWORDS[v]);

        boolean[][] base = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(base, fn, v + 1, size);
        placeData(base, fn, all, size);

        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = new boolean[size][size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    m[y][x] = base[y][x] ^ (!fn[y][x] && masked(mask, x, y));
                }
            }
            drawFormat(m, mask, size);
            int p = penalty(m, size);
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
        }
        return best;
    }

    private static void append(StringBuilder sb, int value, int len) {
        for (int i = len - 1; i >= 0; i--) {
            sb.append((value >>> i) & 1);
        }
    }

    private static boolean masked(int mask, int x, int y) {
        return switch (mask) {
            case 0 -> (x + y) % 2 == 0;
            case 1 -> y % 2 == 0;
            case 2 -> x % 3 == 0;
            case 3 -> (x + y) % 3 == 0;
            case 4 -> (x / 3 + y / 2) % 2 == 0;
            case 5 -> x * y % 2 + x * y % 3 == 0;
            case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
            default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
        };
    }

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        if (fn != null) {
            fn[y][x] = true;
        }
    }

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, int version, int size) {
        for (int i = 0; i < size; i++) {
            set(m, fn, 6, i, i % 2 == 0);
            set(m, fn, i, 6, i % 2 == 0);
        }
        int[][] centers = {{3, 3}, {size - 4, 3}, {3, size - 4}};
        for (int[] c : centers) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = c[0] + dx;
                    int y = c[1] + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) {
                        int d = Math.max(Math.abs(dx), Math.abs(dy));
                        set(m, fn, x, y, d != 2 && d != 4);
                    }
                }
            }
        }
        if (version >= 2) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    set(m, fn, size - 7 + dx, size - 7 + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        markFormat(fn, size); // the format areas are drawn per mask, never by placeData
        fn[size - 8][8] = true;
    }

    private static void markFormat(boolean[][] fn, int size) {
        for (int i = 0; i < 9; i++) {
            fn[8][i] = true;
            fn[i][8] = true;
        }
        for (int i = 0; i < 8; i++) {
            fn[8][size - 1 - i] = true;
            fn[size - 1 - i][8] = true;
        }
    }

    private static void drawFormat(boolean[][] m, int mask, int size) {
        int data = (1 << 3) | mask; // level L = 01
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(m, null, 8, i, bit(bits, i));
        }
        set(m, null, 8, 7, bit(bits, 6));
        set(m, null, 8, 8, bit(bits, 7));
        set(m, null, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            set(m, null, 14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            set(m, null, size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            set(m, null, 8, size - 15 + i, bit(bits, i));
        }
        set(m, null, 8, size - 8, true); // the always-dark module
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    private static void placeData(boolean[][] m, boolean[][] fn, byte[] data, int size) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    int y = ((right + 1) & 2) == 0 ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        m[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static int penalty(boolean[][] m, int size) {
        int p = 0;
        int dark = 0;
        for (int a = 0; a < size; a++) {
            for (int dir = 0; dir < 2; dir++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean same = dir == 0 ? m[a][b] == m[a][b - 1] : m[b][a] == m[b - 1][a];
                    run = same ? run + 1 : 1;
                    if (run == 5) {
                        p += 3;
                    } else if (run > 5) {
                        p++;
                    }
                }
            }
            for (int b = 0; b < size; b++) {
                dark += m[a][b] ? 1 : 0;
                if (a + 1 < size && b + 1 < size && m[a][b] == m[a + 1][b] && m[a][b] == m[a][b + 1] && m[a][b] == m[a + 1][b + 1]) {
                    p += 3;
                }
            }
        }
        int total = size * size;
        return p + 10 * ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1);
    }

    private static byte[] rsRemainder(byte[] data, int degree) {
        int[] gen = new int[degree];
        gen[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                gen[j] = mul(gen[j], root);
                if (j + 1 < degree) {
                    gen[j] ^= gen[j + 1];
                }
            }
            root = mul(root, 2);
        }
        int[] result = new int[degree];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ result[0];
            System.arraycopy(result, 1, result, 0, degree - 1);
            result[degree - 1] = 0;
            for (int i = 0; i < degree; i++) {
                result[i] ^= mul(gen[i], factor);
            }
        }
        byte[] out = new byte[degree];
        for (int i = 0; i < degree; i++) {
            out[i] = (byte) result[i];
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
}
