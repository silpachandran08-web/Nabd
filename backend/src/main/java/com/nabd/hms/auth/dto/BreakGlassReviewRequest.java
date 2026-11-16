package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PLT-013: note is the incident note when outcome is not_justified (required then); revokePermission removes
 * clinical:breakGlass from the holder and is only valid with not_justified. The note is never logged or audited (free text). */
public record BreakGlassReviewRequest(
        @NotBlank @Pattern(regexp = "justified|not_justified") String outcome,
        @Size(max = 1000) String note,
        Boolean revokePermission) {
}
