package com.nabd.hms.organization.golive;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADM-011: the poster's QR encoder. Structure is asserted here; a camera-grade decode is checked by scripts/ci/qr_decode_check.py on the file this writes. */
class QrCodeTest {

    @Test
    void sizesFollowTheVersionAndTheThreeFindersAndTimingAreInPlace() {
        boolean[][] small = QrCode.encode("https://x.example/b/abc");
        assertThat(small.length).isEqualTo(25); // 23 bytes need 25 data codewords: version 2 (34)
        boolean[][] tiny = QrCode.encode("hi");
        assertThat(tiny.length).isEqualTo(21);
        for (boolean[][] m : List.of(small, tiny)) {
            int n = m.length;
            for (int[] o : new int[][] {{0, 0}, {0, n - 7}, {n - 7, 0}}) {
                for (int i = 0; i < 7; i++) {
                    assertThat(m[o[0]][o[1] + i]).isTrue();
                    assertThat(m[o[0] + 6][o[1] + i]).isTrue();
                    assertThat(m[o[0] + i][o[1]]).isTrue();
                    assertThat(m[o[0] + i][o[1] + 6]).isTrue();
                }
                assertThat(m[o[0] + 3][o[1] + 3]).isTrue();
                assertThat(m[o[0] + 1][o[1] + 1]).isFalse();
            }
            for (int i = 8; i < n - 8; i++) {
                assertThat(m[6][i]).isEqualTo(i % 2 == 0);
                assertThat(m[i][6]).isEqualTo(i % 2 == 0);
            }
            assertThat(m[n - 8][8]).as("always-dark module").isTrue();
        }
    }

    @Test
    void formatInformationIsAValidBchWord_inBothCopies() {
        boolean[][] m = QrCode.encode("https://x.example/b/abc");
        int n = m.length;
        int a = 0;
        int[][] first = {{0, 8}, {1, 8}, {2, 8}, {3, 8}, {4, 8}, {5, 8}, {7, 8}, {8, 8}, {8, 7}, {8, 5}, {8, 4}, {8, 3}, {8, 2}, {8, 1}, {8, 0}}; // {row, col} for bits 0..14
        for (int i = 0; i < 15; i++) {
            a |= (m[first[i][0]][first[i][1]] ? 1 : 0) << i;
        }
        int b = 0;
        for (int i = 0; i < 8; i++) {
            b |= (m[8][n - 1 - i] ? 1 : 0) << i;
        }
        for (int i = 8; i < 15; i++) {
            b |= (m[n - 15 + i][8] ? 1 : 0) << i;
        }
        assertThat(b).isEqualTo(a);
        int word = a ^ 0x5412;
        assertThat(word >>> 10).as("level L").isBetween(8, 15);
        int rem = word >>> 10;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        assertThat(rem & 0x3FF).isEqualTo(word & 0x3FF);
    }

    @Test
    void tooLongTextIsRefused_andTheMaximumFits() {
        assertThat(QrCode.encode("a".repeat(106)).length).isEqualTo(37);
        assertThatThrownBy(() -> QrCode.encode("a".repeat(107))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesSamplesForTheDecodeCheck() throws Exception {
        String dir = System.getProperty("qr.sample.dir");
        if (dir == null) {
            return;
        }
        for (String text : new String[] {"https://clinic.example/b/t1a2b3c4d5e6f", "hi", "https://book.example.org/b/" + "x".repeat(70)}) {
            List<String> lines = new ArrayList<>();
            lines.add(text);
            for (boolean[] row : QrCode.encode(text)) {
                StringBuilder sb = new StringBuilder();
                for (boolean d : row) {
                    sb.append(d ? '1' : '0');
                }
                lines.add(sb.toString());
            }
            Files.write(Path.of(dir, "qr-" + Math.abs(text.hashCode()) + ".txt"), lines);
        }
    }
}
