package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.CatalogueTemplates;
import com.nabd.hms.common.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** COM-012: copies the platform template catalogue into a tenant as queued whatsapp_templates rows (header = clinic name,
 * NB-198). Submission to Meta happens later in CatalogueSubmissionJob, paced for Meta's template-creation rate limit. */
@Service
public class TemplateCatalogueService implements CatalogueTemplates {

    private static final List<String> SUPPORTED = List.of("en", "hi", "kn", "te");

    private final TemplateCatalogueRepository catalogue;
    private final WhatsAppTemplateRepository templates;
    private final TenantScope tenantScope;

    TemplateCatalogueService(TemplateCatalogueRepository catalogue, WhatsAppTemplateRepository templates, TenantScope tenantScope) {
        this.catalogue = catalogue;
        this.templates = templates;
        this.tenantScope = tenantScope;
    }

    /** India: English, Hindi and the clinic's state language (kn or te when known); GCC: English until Phase 4 Arabic. */
    public static List<String> defaultLanguages(String region, String stateLanguage) {
        List<String> out = new ArrayList<>(List.of("en"));
        if ("IN".equals(region)) {
            out.add("hi");
            if ("kn".equals(stateLanguage) || "te".equals(stateLanguage)) {
                out.add(stateLanguage);
            }
        }
        return out;
    }

    @Override
    public int instantiateDefaults(UUID tenantId, String region) {
        // shortcut: no clinic state is stored yet, so India tenants get EN + HI; pass the state language once it exists
        return instantiate(tenantId, defaultLanguages(region, null), false); // a saga retry finds them already there
    }

    @Override
    public void removeInstantiated(UUID tenantId) {
        tenantScope.run(tenantId, () -> catalogue.deleteInstantiated(tenantId));
    }

    public int instantiate(UUID tenantId, List<String> languages) {
        return instantiate(tenantId, languages, true);
    }

    private int instantiate(UUID tenantId, List<String> languages, boolean conflictIfPresent) {
        List<String> langs = languages == null ? List.of() : languages.stream().map(l -> l.toLowerCase(Locale.ROOT)).distinct().toList();
        if (langs.isEmpty() || !SUPPORTED.containsAll(langs)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-catalogue-languages", "Unsupported language",
                    "Languages must be a non-empty subset of " + SUPPORTED + ".");
        }
        return tenantScope.run(tenantId, () -> {
            if (catalogue.instantiated(tenantId)) {
                if (!conflictIfPresent) {
                    return 0;
                }
                throw new ApiException(HttpStatus.CONFLICT, "already-instantiated", "Catalogue already instantiated",
                        "This clinic already has its starter templates.", java.util.Map.of("code", "ALREADY_INSTANTIATED"));
            }
            WhatsAppTemplateRepository.Clinic clinic = templates.clinic(tenantId);
            String header = WhatsAppTemplateService.header(clinic);
            String slug = clinic.slug().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            List<TemplateCatalogueRepository.Entry> entries = catalogue.latest(langs);
            entries.forEach(e -> catalogue.insertQueued(tenantId, e, slug + "_" + e.key() + "_" + e.language(), header));
            return entries.size();
        });
    }
}
