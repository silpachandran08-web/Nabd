package com.nabd.hms.clinical;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/** CLN-018: which licensed drug knowledge base (and which monthly data load) is behind the drug data, so the UI can show
 * "Drug data: &lt;KB&gt; &lt;month&gt;". Attribution only: the licence forbids exposing KB content through public APIs, so
 * this is the whole KB surface (plus the capped typeahead). Not configured (no signed licence / no feed loaded) means
 * configured=false and the UI shows nothing. */
@RestController
class DrugKnowledgeBaseController {

    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    @Schema(name = "DrugKnowledgeBaseSource")
    public record Source(boolean configured, String source, String dataMonth) {
    }

    private final Source source;

    DrugKnowledgeBaseController(@Value("${app.kb.source-name:}") String name, @Value("${app.kb.data-month:}") String month) {
        boolean ok = !name.isBlank() && MONTH.matcher(month.trim()).matches();
        this.source = ok ? new Source(true, name.trim(), month.trim()) : new Source(false, null, null);
    }

    @GetMapping("/v1/clinical/drug-knowledge-base")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view') or hasAuthority('clinical:edit')")
    public Source get() {
        return source;
    }
}
