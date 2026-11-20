package com.nabd.comms.i18n;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FEF-041: the gateway reads the same published artefact as patient-web (scripts/ci/patient_catalogue.py keeps the two copies identical). */
class PatientCatalogueTest {

    private final PatientCatalogue cat = PatientCatalogue.get();

    @Test
    void everyTemplateExistsInEnglishHindiKannadaAndTelugu() {
        for (String key : List.of("appt_confirmed", "appt_reminder_24h", "appt_nudge", "package_session_nudge", "package_grace", "queue_paused", "secure_link_ready", "otp_login")) {
            for (String lang : List.of("en", "hi", "kn", "te")) {
                assertThat(cat.has(key, lang)).as(key + "/" + lang).isTrue();
            }
        }
    }

    @Test
    void rendersTheTemplateTextInThePatientsLanguage() {
        assertThat(cat.render("appt_reminder_2h", "en", List.of("Asha", "10:30"))).contains("Hello Asha, your appointment is today at 10:30. We look forward to seeing you.");
        assertThat(cat.render("appt_reminder_2h", "te", List.of("Asha", "10:30"))).get().asString().contains("Asha").contains("10:30").contains("మీ సందర్శన");
        assertThat(cat.render("queue_paused", "kn", List.of())).isPresent();
    }

    @Test
    void aMissingKeyLanguageOrSlotIsNeverFilledWithEnglishOrALeftoverPlaceholder() {
        assertThat(cat.render("no_such_template", "te", List.of())).isEmpty();
        assertThat(cat.render("appt_reminder_2h", "ta", List.of("A", "B"))).isEmpty();
        assertThat(cat.render("appt_reminder_2h", "hi", List.of("Asha"))).isEmpty();
    }

    @Test
    void machineDraftsAreMarkedUnreviewedAndEnglishIsTheSource() {
        assertThat(cat.reviewStatus("appt_reminder_2h", "en")).isEqualTo("source");
        for (String lang : List.of("hi", "kn", "te")) {
            assertThat(cat.reviewStatus("appt_reminder_2h", lang)).isEqualTo("unreviewed");
        }
    }

    @Test
    void theVersionIsAContentHash() {
        assertThat(cat.version()).matches("[0-9a-f]{12}");
        var other = new PatientCatalogue(JsonMapper.builder().build().readTree("{\"version\":\"abc\",\"templates\":{}}"));
        assertThat(other.version()).isEqualTo("abc");
    }
}
