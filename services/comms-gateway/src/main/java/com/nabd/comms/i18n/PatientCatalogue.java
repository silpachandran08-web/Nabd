package com.nabd.comms.i18n;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * FEF-041: the patient language catalogue (en, hi, kn, te) published from packages/i18n as i18n/patient-catalogue.json, the same file
 * patient-web is built from. {@link #version()} is stamped on every outbound message (outbound_messages.catalogue_version), so a send
 * can be traced to the exact text, including its review state. Rendering fills the template's {{n}} slots; WhatsApp templates are shown
 * to the patient from Meta's approved copy of the same body, so this is the reference text for previews and the SMS fallback.
 * Loaded once from the classpath: the artefact is part of the build, never read from a tenant or a request.
 */
public final class PatientCatalogue {

    static final String RESOURCE = "/i18n/patient-catalogue.json";
    private static final PatientCatalogue INSTANCE = load(RESOURCE);

    private final String version;
    private final JsonNode templates;
    private final JsonNode review;

    PatientCatalogue(JsonNode root) {
        this.version = root.path("version").asString("");
        this.templates = root.path("templates");
        this.review = root.path("review");
        if (version.isBlank() || templates.isMissingNode()) {
            throw new IllegalStateException("patient catalogue artefact is malformed");
        }
    }

    public static PatientCatalogue get() {
        return INSTANCE;
    }

    static PatientCatalogue load(String resource) {
        try (InputStream in = PatientCatalogue.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("patient catalogue artefact missing: " + resource);
            }
            return new PatientCatalogue(JsonMapper.builder().build().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("patient catalogue artefact unreadable", e);
        }
    }

    /** Artefact version (content hash); the value recorded per send. */
    public String version() {
        return version;
    }

    public boolean has(String key, String language) {
        return templates.path(key).path(language).has("body");
    }

    /** The template text with {{1}}, {{2}}... replaced; empty when the key or language is not in the catalogue (no silent English fallback). */
    public Optional<String> render(String key, String language, List<String> params) {
        JsonNode body = templates.path(key).path(language).path("body");
        if (body.isMissingNode()) {
            return Optional.empty();
        }
        Matcher m = java.util.regex.Pattern.compile("\\{\\{(\\d+)}}").matcher(body.asString());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1)) - 1;
            if (i < 0 || i >= params.size()) {
                return Optional.empty(); // a missing slot must never reach a patient as "{{2}}"
            }
            m.appendReplacement(out, Matcher.quoteReplacement(params.get(i)));
        }
        return Optional.of(m.appendTail(out).toString());
    }

    /** "reviewed" (native clinician, with reviewer id and date in the artefact) or "unreviewed" (machine draft). English is the source text. */
    public String reviewStatus(String key, String language) {
        if ("en".equals(language)) {
            return "source";
        }
        return review.path(language).path("templates").path(key).path("status").asString("unreviewed");
    }
}
