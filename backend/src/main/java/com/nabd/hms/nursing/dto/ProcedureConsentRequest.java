package com.nabd.hms.nursing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * CLN-014: a signed consent form (templateId ... lang), or NB-119's typed name (signedName alone), which is accepted only for a
 * procedure the clinic has no consent form for. Signatures are SVG path strokes ("M x y L x y ...") in a 600 x 200 box.
 */
public record ProcedureConsentRequest(
        @Size(max = 200) String signedName,
        UUID templateId,
        Integer templateVersion,
        @Valid Signer signer,
        @Size(max = 200000) String signatureSvg,
        @Size(max = 200000) String assentSignatureSvg,
        UUID witnessStaffId,
        @Pattern(regexp = "en|hi|kn|te|ar") String lang
) {
    public record Signer(@NotBlank @Pattern(regexp = "patient|guardian") String kind, UUID guardianId, @NotBlank @Size(max = 200) String name) {
    }
}
