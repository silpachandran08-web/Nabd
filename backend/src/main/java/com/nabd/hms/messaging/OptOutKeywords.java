package com.nabd.hms.messaging;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * COM-002: what an inbound WhatsApp message means for consent. Only a message that IS a keyword counts (the whole text, after
 * normalising), so "please do not stop my reminders" opts nobody out. Quick-reply button payloads are matched first.
 * The lists are versioned: change VERSION with every edit. Kannada, Telugu and Arabic entries need review by a native speaker
 * of each language before launch (NFR "reviewed per language"); English and Hindi follow the story text.
 */
final class OptOutKeywords {

    enum Intent { STOP_ALL, STOP_MARKETING, START, NONE }

    static final String VERSION = "2026-10-1";

    private static final Set<String> STOP = Set.of(
            "stop", "stop all", "unsubscribe", "cancel subscription", "opt out", "optout",           // en
            "रोकें", "रोको", "बंद", "बंद करें", "रुकें",                                                  // hi
            "ನಿಲ್ಲಿಸಿ", "ನಿಲ್ಲಿಸು",                                                                       // kn
            "ఆపండి", "ఆపు",                                                                          // te
            "بند", "ايقاف", "توقف", "الغاء الاشتراك");                                                // ar (normalised: hamza and diacritics removed)
    private static final Set<String> STOP_MARKETING = Set.of(
            "stop promotions", "stop marketing", "unsubscribe from promotions", "no promotions");
    private static final Set<String> START = Set.of(
            "start", "subscribe", "opt in", "optin",
            "शुरू", "शुरू करें",
            "ಪ್ರಾರಂಭ", "ప్రారంభం", "ابدا");
    /** Button payload ids our templates carry (COM-014 owns the templates and must use these ids). */
    private static final Map<String, Intent> PAYLOADS = Map.of(
            "STOP", Intent.STOP_ALL, "STOP_ALL", Intent.STOP_ALL, "OPT_OUT", Intent.STOP_ALL,
            "STOP_PROMOTIONS", Intent.STOP_MARKETING, "STOP_MARKETING", Intent.STOP_MARKETING,
            "START", Intent.START, "OPT_IN", Intent.START);

    private OptOutKeywords() {
    }

    static Intent classify(String text, String buttonPayload) {
        if (buttonPayload != null) {
            Intent p = PAYLOADS.get(buttonPayload.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
            if (p != null) {
                return p;
            }
        }
        String n = normalise(text);
        if (n.isEmpty()) {
            return Intent.NONE;
        }
        // marketing first: "stop promotions" must never fall through to a full STOP
        return STOP_MARKETING.contains(n) ? Intent.STOP_MARKETING : STOP.contains(n) ? Intent.STOP_ALL : START.contains(n) ? Intent.START : Intent.NONE;
    }

    /** NFC, case-folded, Arabic diacritics/tatweel dropped and alef/hamza variants merged, edge punctuation trimmed, spaces collapsed. */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").replaceAll("[\\u0622\\u0623\\u0625]", "ا");
        s = s.replaceAll("^[^\\p{L}\\p{M}\\p{N}]+|[^\\p{L}\\p{M}\\p{N}]+$", ""); // trailing "!", "." , Arabic and Devanagari danda ...
        return s.replaceAll("\\s+", " ").trim();
    }
}
