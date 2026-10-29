package com.nabd.comms.inbox;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * COM-032: two keyword checks, nothing more. They only SUGGEST: a person tags the thread or overrides the warning. Neither infers a diagnosis.
 * The word lists are plain data for a clinician to review (Needs clinical sign-off); English only, other languages are added the same way.
 */
public final class ClinicalText {

    /** Inbound text that suggests the thread is clinical (the four words the story names). */
    static final List<String> SYMPTOM_WORDS = List.of("rash", "bleeding", "pain after", "side effect");

    /** Outbound text that looks like a result or a diagnosis: staff are pointed to a secure link instead. */
    private static final Pattern RESULT_OR_DIAGNOSIS = Pattern.compile(
            "\\b(diagnos(is|ed)|test results?|lab results?|report shows|biopsy|hba1c|you (have|tested) (positive|negative)|(positive|negative) for)\\b"
                    + "|\\b\\d+(\\.\\d+)?\\s?(mg/dl|mmol/l|g/dl|iu/l)\\b", Pattern.CASE_INSENSITIVE);

    private ClinicalText() {
    }

    public static boolean suggestsClinical(String inbound) {
        if (inbound == null) {
            return false;
        }
        String t = inbound.toLowerCase(Locale.ROOT);
        return SYMPTOM_WORDS.stream().anyMatch(t::contains);
    }

    public static boolean looksLikeResultOrDiagnosis(String outbound) {
        return outbound != null && RESULT_OR_DIAGNOSIS.matcher(outbound).find();
    }
}
