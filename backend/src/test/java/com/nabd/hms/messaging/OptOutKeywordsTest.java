package com.nabd.hms.messaging;

import com.nabd.hms.messaging.OptOutKeywords.Intent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-002 unit: the keyword matcher across the five launch languages, case, diacritics and trailing punctuation. */
class OptOutKeywordsTest {

    @Test
    void englishAnyCaseWithPunctuationAndSpaces() {
        for (String s : new String[] {"STOP", "stop", "Stop", "  stop  ", "Stop!", "stop.", "STOP\n", "UNSUBSCRIBE", "Unsubscribe.", "stop   all", "“stop”"}) {
            assertThat(OptOutKeywords.classify(s, null)).as(s).isEqualTo(Intent.STOP_ALL);
        }
    }

    @Test
    void hindiKannadaTeluguArabic() {
        for (String s : new String[] {"रोकें", "रोकें।", "रोकें!", "बंद करें", "ನಿಲ್ಲಿಸಿ", "ನಿಲ್ಲಿಸಿ.", "ఆపండి", "ఆపండి!", "بند", "إيقاف", "ايقاف", "إِيقَاف", "إيقاف!", "توقف", "توقّف"}) {
            assertThat(OptOutKeywords.classify(s, null)).as(s).isEqualTo(Intent.STOP_ALL);
        }
    }

    @Test
    void arabicDiacriticsAndTatweelAreIgnored() {
        assertThat(OptOutKeywords.normalise("إِيقَـاف")).isEqualTo("ايقاف");
    }

    @Test
    void stopPromotionsIsMarketingOnlyAndNeverAFullStop() {
        assertThat(OptOutKeywords.classify("Stop promotions", null)).isEqualTo(Intent.STOP_MARKETING);
        assertThat(OptOutKeywords.classify("STOP PROMOTIONS.", null)).isEqualTo(Intent.STOP_MARKETING);
        assertThat(OptOutKeywords.classify("anything", "STOP_PROMOTIONS")).isEqualTo(Intent.STOP_MARKETING);
        assertThat(OptOutKeywords.classify(null, "stop-promotions")).isEqualTo(Intent.STOP_MARKETING);
    }

    @Test
    void buttonPayloadsAndStart() {
        assertThat(OptOutKeywords.classify("whatever", "STOP")).isEqualTo(Intent.STOP_ALL);
        assertThat(OptOutKeywords.classify("START", null)).isEqualTo(Intent.START);
        assertThat(OptOutKeywords.classify("start!", null)).isEqualTo(Intent.START);
        assertThat(OptOutKeywords.classify(null, "OPT_IN")).isEqualTo(Intent.START);
        assertThat(OptOutKeywords.classify("शुरू", null)).isEqualTo(Intent.START);
        assertThat(OptOutKeywords.classify("ابدأ", null)).isEqualTo(Intent.START);
    }

    @Test
    void aSentenceThatMerelyContainsAKeywordOptsNobodyOut() {
        for (String s : new String[] {"please do not stop my reminders", "can you stop by tomorrow", "stop it now please", "I will start tomorrow", "",
                "   ", "hello", "रोकें नहीं", "yes", "1"}) {
            assertThat(OptOutKeywords.classify(s, null)).as(s).isEqualTo(Intent.NONE);
        }
        assertThat(OptOutKeywords.classify(null, null)).isEqualTo(Intent.NONE);
        assertThat(OptOutKeywords.classify("hi", "SOMETHING_ELSE")).isEqualTo(Intent.NONE);
    }
}
