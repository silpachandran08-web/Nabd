package com.nabd.hms.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Meta WhatsApp Cloud API — POST {base}/{phone-number-id}/messages. */
class MetaWhatsAppClient implements WhatsAppClient {

    private final RestClient http;
    private final String phoneNumberId;
    private final String businessAccountId;

    MetaWhatsAppClient(WhatsAppProperties props) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5000);
        timeouts.setReadTimeout(10000);
        this.http = RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.accessToken())
                .requestFactory(timeouts)
                .build();
        this.phoneNumberId = props.phoneNumberId();
        this.businessAccountId = props.businessAccountId();
    }

    @Override
    public String sendTemplate(String toPhone, String templateName, String language, List<String> bodyParams) {
        List<Map<String, Object>> components = bodyParams.isEmpty() ? List.of()
                : List.of(Map.of("type", "body", "parameters", textParams(bodyParams)));
        return send(toPhone, templateName, language, components);
    }

    @Override
    public String sendOtp(String toPhone, String templateName, String language, String code) {
        return send(toPhone, templateName, language, List.of(
                Map.of("type", "body", "parameters", textParams(List.of(code))),
                Map.of("type", "button", "sub_type", "url", "index", "0", "parameters", textParams(List.of(code)))));
    }

    @Override
    public Submission createTemplate(String metaName, String language, String category, String headerText,
                                     String body, List<String> bodyExamples) {
        JsonNode resp = http.post().uri("/{waba}/message_templates", businessAccountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", metaName, "language", language, "category", category,
                        "components", templateComponents(headerText, body, bodyExamples)))
                .retrieve().body(JsonNode.class);
        return new Submission(resp.path("id").asText(), resp.path("status").asText("PENDING").toLowerCase());
    }

    @Override
    public void editTemplate(String metaTemplateId, String headerText, String body, List<String> bodyExamples) {
        http.post().uri("/{id}", metaTemplateId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("components", templateComponents(headerText, body, bodyExamples)))
                .retrieve().toBodilessEntity();
    }

    private static List<Map<String, Object>> templateComponents(String headerText, String body, List<String> bodyExamples) {
        Map<String, Object> bodyComponent = bodyExamples.isEmpty()
                ? Map.of("type", "BODY", "text", body)
                : Map.of("type", "BODY", "text", body, "example", Map.of("body_text", List.of(bodyExamples)));
        return List.of(Map.of("type", "HEADER", "format", "TEXT", "text", headerText), bodyComponent);
    }

    private String send(String toPhone, String templateName, String language, List<Map<String, Object>> components) {
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone.replaceAll("\\D", ""), // Meta wants bare digits, country code included
                "type", "template",
                "template", Map.of("name", templateName, "language", Map.of("code", language), "components", components));
        // 4xx/5xx throw RestClientResponseException — the outbox retries it, OTP callers see it fail
        JsonNode resp = http.post().uri("/{id}/messages", phoneNumberId)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
        return resp.path("messages").path(0).path("id").asText();
    }

    private static List<Map<String, String>> textParams(List<String> values) {
        return values.stream().map(v -> Map.of("type", "text", "text", v)).toList();
    }
}
