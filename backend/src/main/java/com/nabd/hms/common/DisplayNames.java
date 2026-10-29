package com.nabd.hms.common;

/** ACC-022: the name a shared screen may show: first name + last initial ("Anita R."). Code-point safe (Arabic, Devanagari). */
public final class DisplayNames {

    private DisplayNames() {
    }

    public static String firstNameLastInitial(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        return parts[0] + " " + new String(Character.toChars(last.codePointAt(0))) + ".";
    }
}
