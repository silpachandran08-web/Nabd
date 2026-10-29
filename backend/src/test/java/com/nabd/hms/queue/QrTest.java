package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACC-033: the QR encoder (structure only; decoding was checked with OpenCV once, see the story report) and the distance maths. */
class QrTest {

    @Test
    void sizeFollowsTheVersionAndTheThreeFindersAndTimingAreInPlace() {
        boolean[][] hello = Qr.encode("HELLO");
        assertThat(hello.length).isEqualTo(21); // version 1
        boolean[][] link = Qr.encode("https://wa.me/919876543210?text=CHECKIN%20AB12CD34");
        assertThat(link.length).isEqualTo(33); // version 4 at level M
        for (boolean[][] m : new boolean[][][]{hello, link}) {
            int n = m.length;
            for (int[] o : new int[][]{{0, 0}, {n - 7, 0}, {0, n - 7}}) { // finder: dark ring, light ring, dark 3x3 centre
                for (int i = 0; i < 7; i++) {
                    assertThat(m[o[1]][o[0] + i]).isTrue();
                    assertThat(m[o[1] + 6][o[0] + i]).isTrue();
                    assertThat(m[o[1] + i][o[0]]).isTrue();
                    assertThat(m[o[1] + i][o[0] + 6]).isTrue();
                }
                assertThat(m[o[1] + 1][o[0] + 1]).isFalse();
                assertThat(m[o[1] + 3][o[0] + 3]).isTrue();
            }
            for (int i = 8; i < n - 8; i++) assertThat(m[6][i]).isEqualTo(i % 2 == 0); // timing
            assertThat(m[n - 8][8]).as("dark module").isTrue();
        }
    }

    @Test
    void sameTextSameCodeDifferentTextDifferentCodeAndTooLongIsRefused() {
        assertThat(Qr.encode("CHECKIN ABC")).isDeepEqualTo(Qr.encode("CHECKIN ABC"));
        assertThat(Qr.encode("CHECKIN ABC")).isNotEqualTo(Qr.encode("CHECKIN ABD"));
        assertThat(Qr.encode("x".repeat(213)).length).isEqualTo(57); // version 10, the largest supported
        assertThatThrownBy(() -> Qr.encode("x".repeat(214))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pngAndPdfAreWellFormed() {
        boolean[][] m = Qr.encode("https://wa.me/1?text=CHECKIN%20ABCDEFGH");
        byte[] png = Qr.png(m, 4);
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        String pdf = new String(Qr.pdf(m, "Scan (to) check in é", "CHECKIN ABCDEFGH"), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(pdf).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("Scan \\(to\\) check in ?").contains("/Count 1");
        int xref = Integer.parseInt(pdf.substring(pdf.indexOf("startxref\n") + 10, pdf.indexOf("\n%%EOF")));
        assertThat(pdf.substring(xref)).startsWith("xref\n0 6");
    }

    @Test
    void haversineKnownDistances() {
        assertThat(SelfCheckInService.haversineM(12.9716, 77.5946, 12.9716, 77.5946)).isZero();
        assertThat(SelfCheckInService.haversineM(0, 0, 0, 1)).isBetween(111_000.0, 111_400.0); // one degree of longitude at the equator
        assertThat(SelfCheckInService.haversineM(12.9716, 77.5946, 12.984191, 77.5946)).isBetween(1390.0, 1410.0);
        assertThat(SelfCheckInService.haversineM(12.9716, 77.5946, 12.9725, 77.5946)).isBetween(95.0, 105.0);
    }
}
