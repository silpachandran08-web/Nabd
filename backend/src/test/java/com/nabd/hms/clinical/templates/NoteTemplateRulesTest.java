package com.nabd.hms.clinical.templates;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOC-027. Pure unit test; written but NOT executed in the authoring environment (no Maven). */
class NoteTemplateRulesTest {

    @Test
    void nameIsTrimmedAndRequired() {
        assertThat(NoteTemplateRules.name("  Follow-up  ")).isEqualTo("Follow-up");
        assertThatThrownBy(() -> NoteTemplateRules.name("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoteTemplateRules.name(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NoteTemplateRules.name("x".repeat(81))).isInstanceOf(ApiException.class);
    }

    @Test
    void sectionsAreKeptAsTypedAndNeverTruncated() {
        assertThat(NoteTemplateRules.section("plan", null)).isEmpty();
        assertThat(NoteTemplateRules.section("plan", "  two  spaces\n")).isEqualTo("  two  spaces\n");
        assertThatThrownBy(() -> NoteTemplateRules.section("plan", "x".repeat(4001))).isInstanceOf(ApiException.class);
    }

    @Test
    void tagsAreTrimmedDeduplicatedCaseInsensitivelyAndBounded() {
        assertThat(NoteTemplateRules.tags(java.util.Arrays.asList(" Cough ", "cough", "", null, "Fever")))
                .containsExactly("Cough", "Fever");
        assertThat(NoteTemplateRules.tags(null)).isEmpty();
        assertThatThrownBy(() -> NoteTemplateRules.tags(List.of("x".repeat(31)))).isInstanceOf(ApiException.class);
        List<String> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();
        assertThatThrownBy(() -> NoteTemplateRules.tags(eleven)).isInstanceOf(ApiException.class);
    }
}
