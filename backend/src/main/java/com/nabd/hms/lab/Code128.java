package com.nabd.hms.lab;

/**
 * PHL-043: Code 128 (ISO/IEC 15417), code set B, with no library. Used for the order reference on the lab order sheet and by
 * PHL-045 for sample labels. Code set B covers ASCII 32 to 126, which is all a reference or accession number needs.
 * shortcut: no code set C, so a long all-digit value is about twice as wide as it could be; add C when a label gets too wide.
 */
public final class Code128 {

    /** Bar and space widths of symbol values 0 to 105 (bar first, 11 modules each); the stop symbol has 13 modules. */
    static final String[] PATTERNS = {
            "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
            "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
            "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
            "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
            "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
            "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
            "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
            "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
            "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
            "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
            "114131", "311141", "411131", "211412", "211214", "211232"};
    static final String STOP = "2331112";
    static final int START_B = 104;

    private Code128() {
    }

    /** Symbol values: start B, one per character, the check symbol. @throws IllegalArgumentException for an empty value or a character outside ASCII 32 to 126 */
    public static int[] values(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("nothing to encode");
        int[] out = new int[text.length() + 2];
        out[0] = START_B;
        int sum = START_B;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) throw new IllegalArgumentException("Code 128 set B cannot encode character " + (int) c);
            out[i + 1] = c - 32;
            sum += (c - 32) * (i + 1);
        }
        out[text.length() + 1] = sum % 103;
        return out;
    }

    /** The whole symbol as modules, '1' = bar and '0' = space, without quiet zones (leave 10 modules free on each side). */
    public static String modules(String text) {
        StringBuilder b = new StringBuilder();
        for (int v : values(text)) append(b, PATTERNS[v]);
        append(b, STOP);
        return b.toString();
    }

    private static void append(StringBuilder b, String widths) {
        for (int i = 0; i < widths.length(); i++) {
            b.append(String.valueOf(i % 2 == 0 ? '1' : '0').repeat(widths.charAt(i) - '0'));
        }
    }
}
