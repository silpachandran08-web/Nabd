package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

/** NB-190 template library. Submission to Meta is synchronous so a rule Meta enforces up front (bad
 * name, duplicate, policy) comes straight back to the clinic; the review verdict arrives later by webhook. */
@Service
public class WhatsAppTemplateService {

    public record TemplateRequest(String name, String language, String category, String body, List<String> examples) {
    }

    public record TemplateResponse(UUID id, String name, String language, String category, String headerText,
                                   String body, int paramCount, String status, String rejectionReason,
                                   String qualityRating, java.time.Instant updatedAt) {
    }

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppClient client;
    private final TenantContext tenantContext;

    WhatsAppTemplateService(WhatsAppTemplateRepository repo, WhatsAppClient client, TenantContext tenantContext) {
        this.repo = repo;
        this.client = client;
        this.tenantContext = tenantContext;
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.list(tenantId).stream().map(WhatsAppTemplateService::toResponse).toList();
    }

    @Transactional
    public TemplateResponse create(UUID tenantId, TemplateRequest req) {
        TemplateRules.checkName(req.name(), req.language());
        if (!"UTILITY".equals(req.category()) && !"MARKETING".equals(req.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-whatsapp-template", "Invalid WhatsApp template",
                    "Category must be UTILITY (reminders, updates) or MARKETING (offers, campaigns).");
        }
        String body = req.body() == null ? null : req.body().strip();
        int paramCount = TemplateRules.checkBody(body, req.examples());
        tenantContext.set(tenantId);
        if (repo.findByName(tenantId, req.name(), req.language()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate-whatsapp-template", "Template already exists",
                    "A template called " + req.name() + " already exists in " + req.language() + ".");
        }
        WhatsAppTemplateRepository.Clinic clinic = repo.clinic(tenantId);
        String header = header(clinic);
        String metaName = clinic.slug().toLowerCase().replaceAll("[^a-z0-9_]", "_") + "_" + req.name();
        WhatsAppClient.Submission submission = submitToMeta(() ->
                client.createTemplate(metaName, req.language(), req.category(), header, body, req.examples()));
        UUID id = repo.insert(tenantId, req.name(), metaName, req.language(), req.category(), header, body, paramCount,
                statusOf(submission.status()), submission.metaTemplateId());
        return toResponse(repo.find(tenantId, id).orElseThrow());
    }

    /** The fix path for a rejected template: new text goes back to Meta for review. */
    @Transactional
    public TemplateResponse resubmit(UUID tenantId, UUID id, TemplateRequest req) {
        tenantContext.set(tenantId);
        WhatsAppTemplateRepository.Template t = repo.find(tenantId, id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "whatsapp-template-not-found", "Template not found", null));
        if (!"rejected".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "whatsapp-template-not-editable", "Template can't be edited",
                    "Only a rejected template can be edited and resubmitted; this one is " + t.status() + ".");
        }
        String body = req.body() == null ? null : req.body().strip();
        int paramCount = TemplateRules.checkBody(body, req.examples());
        String header = header(repo.clinic(tenantId));
        submitToMeta(() -> {
            client.editTemplate(t.metaTemplateId(), header, body, req.examples());
            return null;
        });
        repo.resubmitted(tenantId, id, header, body, paramCount);
        return toResponse(repo.find(tenantId, id).orElseThrow());
    }

    private static String header(WhatsAppTemplateRepository.Clinic clinic) {
        String name = clinic.name().strip();
        return name.length() <= TemplateRules.MAX_HEADER ? name : name.substring(0, TemplateRules.MAX_HEADER).strip();
    }

    /** Meta's own 4xx verdicts (policy, duplicates, formatting) go back to the clinic verbatim. */
    private static <T> T submitToMeta(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "whatsapp-template-refused",
                        "Meta refused the template", metaMessage(e));
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "whatsapp-unavailable", "WhatsApp is unavailable",
                    "Couldn't reach WhatsApp to submit the template. Try again in a few minutes.");
        }
    }

    private static String metaMessage(RestClientResponseException e) {
        try {
            var err = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString()).path("error");
            String userMsg = err.path("error_user_msg").asText("");
            return userMsg.isBlank() ? err.path("message").asText("Rejected by Meta") : userMsg;
        } catch (Exception parseFailure) {
            return "Rejected by Meta";
        }
    }

    /** Meta's statuses, lower-cased, collapsed onto ours; anything unexpected is treated as still in review. */
    static String statusOf(String metaStatus) {
        return switch (metaStatus == null ? "" : metaStatus.toLowerCase()) {
            case "approved", "reinstated" -> "approved";
            case "flagged" -> "flagged";
            case "paused" -> "paused";
            case "disabled" -> "disabled";
            case "rejected" -> "rejected";
            default -> "pending";
        };
    }

    private static TemplateResponse toResponse(WhatsAppTemplateRepository.Template t) {
        return new TemplateResponse(t.id(), t.name(), t.language(), t.category(), t.headerText(), t.body(),
                t.paramCount(), t.status(), t.rejectionReason(), t.qualityRating(), t.updatedAt());
    }
}
