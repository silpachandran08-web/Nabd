package com.nabd.hms.patient;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * OPS-009: Java twins of the SQL functions normalise_name() and nsn_digits() (V1930). Both are pinned to the same
 * golden file (src/test/resources/ops009-search-golden.tsv) in Java and in SQL, so the query side and the stored side
 * cannot drift apart. shortcut: calling codes for IN/SA/AE/QA only until the regions table (OPS-061) exists.
 */
final class SearchKeys {

    private static final Pattern MARKS = Pattern.compile("[\\u064B-\\u0652\\u0670\\u0640]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern INTERNATIONAL = Pattern.compile("^\\s*(\\+|00).*", Pattern.DOTALL);

    private SearchKeys() {
    }

    /** lower case, harakat/tatweel stripped, alef/yaa/taa-marbuta folded, whitespace collapsed. */
    static String normaliseName(String s) {
        String t = MARKS.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
        t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه');
        return SPACES.matcher(t).replaceAll(" ").strip();
    }

    /** National significant number digits: calling code stripped when typed with + or 00, else the trunk 0. */
    static String nsnDigits(String p) {
        String d = p.replaceAll("\\D", "");
        if (INTERNATIONAL.matcher(p).matches()) {
            return d.replaceFirst("^00", "").replaceFirst("^(91|966|971|974)", "");
        }
        return d.replaceFirst("^0", "");
    }

    static String last10(String p) {
        String n = nsnDigits(p);
        return n.length() > 10 ? n.substring(n.length() - 10) : n;
    }
}
