package com.nabd.hms.common;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PLT-054: the rules clinic staff (staff.TrainingService) and Nabd operators (platform.compliance) share for the security
 * training: which modules exist, the pass mark and the yearly refresher. The module text and quiz live in the staff app
 * (help catalogue, en + hi); the server only records a pass.
 */
public final class TrainingRules {

    /** The module every account must hold a current pass of before real patient data. */
    public static final String REQUIRED_MODULE = "phi-handling";
    public static final List<String> MODULES = List.of(REQUIRED_MODULE);
    public static final int PASS_MARK = 80;

    private TrainingRules() {
    }

    /** shortcut: the quiz is marked in the browser and the score is taken as sent (an attestation, like a signed sheet); move the answer key here if it ever has to be an exam. */
    public static void check(String moduleId, int score) {
        if (!MODULES.contains(moduleId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-training-module", "Unknown training module",
                    "No training module has this id.", Map.of("code", "UNKNOWN_TRAINING_MODULE"));
        }
        if (score < PASS_MARK || score > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "training-not-passed", "Module not passed",
                    "The pass mark is " + PASS_MARK + " out of 100. Read the module again and retake the quiz.",
                    Map.of("code", "TRAINING_NOT_PASSED", "passMark", PASS_MARK));
        }
    }

    /** A pass is current for one year; the refresher is due on this instant. */
    public static Instant refresherDue(Instant completedAt) {
        return completedAt.atZone(ClinicClock.UTC).plusYears(1).toInstant();
    }

    /** The same rule in SQL for a completed_at column aliased c: a current pass at the bound instant. */
    public static final String CURRENT_SQL = "c.completed_at + interval '1 year' > ?";
}
