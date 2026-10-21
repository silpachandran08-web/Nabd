package com.nabd.hms.platform.access;

/** PLT-057: what support may see of a patient. Pure functions so the contract is unit-testable. */
final class PatientMasking {

    private PatientMasking() {
    }

    /** "Asha Rao" -> "AR". At most three letters; null for a blank name. */
    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : name.strip().split("\\s+")) {
            if (sb.codePointCount(0, sb.length()) == 3) {
                break;
            }
            sb.appendCodePoint(Character.toUpperCase(part.codePointAt(0)));
        }
        return sb.toString();
    }

    /** "+919876543210" -> "+91******3210": country code and last four digits stay. Fewer than 8 digits are fully hidden. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String p = phone.replaceAll("[\\s()-]", "");
        int cc = p.startsWith("+966") ? 4 : p.startsWith("+91") ? 3 : p.startsWith("+") ? 3 : 0;
        if (p.length() < cc + 8) {
            return "*".repeat(p.length());
        }
        return p.substring(0, cc) + "*".repeat(p.length() - cc - 4) + p.substring(p.length() - 4);
    }
}
