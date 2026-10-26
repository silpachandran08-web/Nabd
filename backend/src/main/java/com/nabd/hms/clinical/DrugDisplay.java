package com.nabd.hms.clinical;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CLN-020/021: how one drug master product reads in a list. generic = ingredient names joined with " + ". Where the
 * region asks for generic first (GP-05): "Amoxicillin 500 mg cap (Mox)"; otherwise brand first: "Mox 500 mg cap (Amoxicillin)". */
final class DrugDisplay {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private DrugDisplay() {
    }

    static String generic(List<String> ingredientNames) {
        return ingredientNames == null || ingredientNames.isEmpty() ? null : String.join(" + ", ingredientNames);
    }

    /** What the Rx line stores as drugName when this product is picked: the generic with strength and form. */
    static String genericLine(String generic, String brand, String strength, String form) {
        return join(generic == null ? brand : generic, strength, form);
    }

    static String display(String generic, String brand, String strength, String form, boolean genericFirst) {
        if (generic == null || generic.equalsIgnoreCase(brand)) {
            return join(brand, strength, form);
        }
        return genericFirst ? join(generic, strength, form) + " (" + brand + ")" : join(brand, strength, form) + " (" + generic + ")";
    }

    /** to_tsquery text for a typed term: every word is a prefix, all must match. Empty when there is no word. */
    static String tsQuery(String q) {
        Matcher m = WORD.matcher(q == null ? "" : q);
        StringBuilder ts = new StringBuilder();
        while (m.find()) {
            ts.append(ts.isEmpty() ? "" : " & ").append(m.group()).append(":*");
        }
        return ts.toString();
    }

    private static String join(String name, String strength, String form) {
        StringBuilder b = new StringBuilder(name);
        if (strength != null && !strength.isBlank()) {
            b.append(' ').append(strength.trim());
        }
        if (form != null && !form.isBlank()) {
            b.append(' ').append(form.trim());
        }
        return b.toString();
    }
}
