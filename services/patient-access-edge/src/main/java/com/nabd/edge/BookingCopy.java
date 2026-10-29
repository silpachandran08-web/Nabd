package com.nabd.edge;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACC-040: the words of the booking flow (resources bot/booking_{en,hi,kn,te,ar}.properties, UTF-8). A message holds the clinic or
 * doctor name, a date, a time or a child's first name and nothing else: no diagnosis, no visit reason. Needs external action: native
 * speaker review of hi / kn / te / ar. A key missing in a language falls back to English.
 */
final class BookingCopy {

    static final List<String> LANGUAGES = List.of("en", "hi", "kn", "te", "ar");
    /** Shown on the language picker in the language itself. */
    static final Map<String, String> NAMES = Map.of("en", "English", "hi", "हिन्दी", "kn", "ಕನ್ನಡ", "te", "తెలుగు", "ar", "العربية");
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();

    private BookingCopy() {
    }

    static boolean supported(String lang) {
        return lang != null && LANGUAGES.contains(lang);
    }

    static String t(String lang, String key, Object... args) {
        String l = supported(lang) ? lang : "en";
        String pattern = load(l).getProperty(key, load("en").getProperty(key));
        if (pattern == null) {
            throw new IllegalArgumentException("no copy for " + key);
        }
        return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
    }

    static Properties load(String lang) {
        return CACHE.computeIfAbsent(lang, l -> {
            Properties p = new Properties();
            try (var in = BookingCopy.class.getResourceAsStream("/bot/booking_" + l + ".properties")) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException | NullPointerException e) {
                throw new IllegalStateException("booking copy missing for " + l, e);
            }
            return p;
        });
    }
}
