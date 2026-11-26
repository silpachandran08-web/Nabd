package com.nabd.hms.lab;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-043: the Code 128 encoder (code set B) used by the lab order sheet and, from PHL-045, the sample labels. */
class Code128Test {

    private static String bits(String widths) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < widths.length(); i++) b.append(String.valueOf(i % 2 == 0 ? '1' : '0').repeat(widths.charAt(i) - '0'));
        return b.toString();
    }

    @Test
    void theSymbolTableIsWellFormed() {
        assertThat(Code128.PATTERNS).hasSize(106);
        assertThat(new HashSet<>(Arrays.asList(Code128.PATTERNS))).as("every symbol is distinct").hasSize(106);
        for (String p : Code128.PATTERNS) {
            assertThat(p).as("three bars and three spaces of 1 to 4 modules").matches("[1-4]{6}");
            assertThat(p.chars().map(c -> c - '0').sum()).as(p + " is 11 modules wide").isEqualTo(11);
            // ISO/IEC 15417: in every symbol the bars add up to an even number of modules
            assertThat((p.charAt(0) - '0') + (p.charAt(2) - '0') + (p.charAt(4) - '0')).as(p + " bar parity").isEven();
        }
        assertThat(Code128.STOP.chars().map(c -> c - '0').sum()).isEqualTo(13);
    }

    /** Bit patterns published with the standard, as an anchor that does not come from this table. */
    @Test
    void knownSymbolsHaveTheirPublishedBars() {
        assertThat(bits(Code128.PATTERNS[103])).as("start A").isEqualTo("11010000100");
        assertThat(bits(Code128.PATTERNS[104])).as("start B").isEqualTo("11010010000");
        assertThat(bits(Code128.PATTERNS[105])).as("start C").isEqualTo("11010011100");
        assertThat(bits(Code128.STOP)).as("stop").isEqualTo("1100011101011");
        assertThat(bits(Code128.PATTERNS[0])).as("space").isEqualTo("11011001100");
        assertThat(bits(Code128.PATTERNS[1])).as("!").isEqualTo("11001101100");
        assertThat(bits(Code128.PATTERNS[16])).as("0").isEqualTo("10011101100");
        assertThat(bits(Code128.PATTERNS[33])).as("A").isEqualTo("10100011000");
        assertThat(bits(Code128.PATTERNS[34])).as("B").isEqualTo("10001011000");
    }

    @Test
    void valuesAreStartBThenTheCharactersThenTheModulo103Check() {
        // the textbook example PJJ123C: weighted sum 775 + start B 104 = 879, 879 mod 103 = 55
        assertThat(Code128.values("PJJ123C")).containsExactly(104, 48, 42, 42, 17, 18, 19, 35, 55);
        assertThat(Code128.values("A")).containsExactly(104, 33, (104 + 33) % 103);
    }

    @Test
    void modulesDecodeBackToTheText() {
        for (String text : new String[]{"LO-0A1B2C3D4E5F", "LX-FFFFFFFFFFFF", "260123-0042", "a~ z"}) {
            String m = Code128.modules(text);
            assertThat(m).as("starts and ends with a bar").startsWith("1").endsWith("1");
            assertThat(m.length()).as("start + characters + check, 11 modules each, and a 13 module stop").isEqualTo((text.length() + 2) * 11 + 13);
            StringBuilder decoded = new StringBuilder();
            int symbols = text.length() + 2;
            int sum = 0;
            for (int s = 0; s < symbols; s++) {
                String chunk = m.substring(s * 11, s * 11 + 11);
                int value = -1;
                for (int v = 0; v < Code128.PATTERNS.length; v++) if (bits(Code128.PATTERNS[v]).equals(chunk)) value = v;
                assertThat(value).as("symbol " + s + " of " + text).isNotNegative();
                if (s == 0) {
                    assertThat(value).isEqualTo(Code128.START_B);
                    sum = value;
                } else if (s < symbols - 1) {
                    decoded.append((char) (value + 32));
                    sum += value * s;
                } else {
                    assertThat(value).as("check symbol").isEqualTo(sum % 103);
                }
            }
            assertThat(decoded.toString()).isEqualTo(text);
            assertThat(m.substring(symbols * 11)).isEqualTo(bits(Code128.STOP));
        }
    }

    @Test
    void whatCodeSetBCannotCarryIsRefused() {
        assertThatThrownBy(() -> Code128.modules("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Code128.modules(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Code128.modules("tab\there")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Code128.modules("café")).isInstanceOf(IllegalArgumentException.class);
    }
}
