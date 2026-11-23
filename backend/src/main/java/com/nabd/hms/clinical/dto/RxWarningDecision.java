package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The doctor's explicit decision on one safety warning of a prescription line: a named reason from the
 * fixed list for that kind (see RxSafetyChecks.reasonOptions). Who and when are stamped by the server. */
public record RxWarningDecision(@NotBlank @Pattern(regexp = "allergy|pregnancy|controlled|ddi|duplicate") String kind,
                                @NotBlank String reasonCode,
                                @Size(max = 500) String note,
                                // CLN-032: the warning's `matched` this decision is for. Required for ddi and duplicate (a line
                                // can carry several, each needs its own decision); optional for the other kinds (null = every
                                // warning of that kind on the line, as before).
                                @Size(max = 500) String matched) {

    /** Keeps the pre-CLN-032 shape compiling. */
    public RxWarningDecision(String kind, String reasonCode, String note) {
        this(kind, reasonCode, note, null);
    }
}
