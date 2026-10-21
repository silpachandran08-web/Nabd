package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.nabd.hms.shared.integration.CircuitOpenException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

/** NB-190 template library. Submission to Meta is synchronous so a rule Meta enforces up front (bad
 * name, duplicate, policy) comes straight back to the clinic; the review verdict arrives later by webhook.
 * OPS-012: create/resubmit are read-check tx, Meta call with no transaction open, write tx — never one
 * transaction around the HTTP call. */
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
    private final TransactionTemplate tx;

    WhatsAppTemplateService(WhatsAppTemplateRepository repo, WhatsAppClient client, TenantContext tenantContext,
                            PlatformTransactionManager txManager) {
        this.repo = repo;
        this.client = client;
        this.tenantContext = tenantContext;
        this.tx = new TransactionTemplate(txManager);
    }

    /** PLT-019: the tenant is the one bound when the transaction started (the signed-in staff member's). */
    private <T> T inTenantTx(java.util.function.Function<UUID, T> work) {
        return tx.execute(status -> work.apply(tenantContext.current()));
    }

    private ApiException duplicate(TemplateRequest req) {
        return new ApiException(HttpStatus.CONFLICT, "duplicate-whatsapp-template", "Template already exists",
                "A template called " + req.name() + " already exists in " + req.language() + ".");
    }

    public List<TemplateResponse> list() {
        return inTenantTx(tenantId -> repo.list(tenantId).stream().map(WhatsAppTemplateService::toResponse).toList());
    }

    public TemplateResponse create(TemplateRequest req) {
        TemplateRules.checkName(req.name(), req.language());
        if (!"UTILITY".equals(req.category()) && !"MARKETING".equals(req.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-whatsapp-template", "Invalid WhatsApp template",
                    "Category must be UTILITY (reminders, updates) or MARKETING (offers, campaigns).");
        }
        String body = req.body() == null ? null : req.body().strip();
        int paramCount = TemplateRules.checkBody(body, req.examples());
        // COM-003 two-phase: (1) the 'submitting' row is committed first, (2) Meta is called with no transaction open,
        // (3) the row is completed. A timeout or a failed write after Meta accepted leaves the row for TemplateReconciler.
        String[] names = new String[2]; // header, metaName
        UUID rowId;
        try {
            rowId = inTenantTx(tenantId -> {
                WhatsAppTemplateRepository.Clinic clinic = repo.clinic(tenantId);
                names[0] = header(clinic);
                names[1] = clinic.slug().toLowerCase().replaceAll("[^a-z0-9_]", "_") + "_" + req.name();
                return repo.insertSubmitting(tenantId, req.name(), names[1], req.language(), req.category(), names[0], body, paramCount);
            });
        } catch (DuplicateKeyException exists) { // same name+language (or Meta name) already taken, or a concurrent create won
            throw duplicate(req);
        }
        WhatsAppClient.Submission submission;
        try {
            submission = client.createTemplate(names[1], req.language(), req.category(), names[0], body, req.examples());
        } catch (RuntimeException e) {
            if (!outcomeUnknown(e)) { // Meta never created it: free the name at once
                inTenantTx(tenantId -> repo.deleteSubmitting(tenantId, rowId));
            }
            throw metaFailure(e);
        }
        return inTenantTx(tenantId -> {
            repo.completeSubmission(tenantId, rowId, statusOf(submission.status()), submission.metaTemplateId());
            return toResponse(repo.find(tenantId, rowId).orElseThrow());
        });
    }

    /** The fix path for a rejected template: new text goes back to Meta for review. */
    public TemplateResponse resubmit(UUID id, TemplateRequest req) {
        WhatsAppTemplateRepository.Template t = inTenantTx(tenantId -> repo.find(tenantId, id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "whatsapp-template-not-found", "Template not found", null)));
        if (!"rejected".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "whatsapp-template-not-editable", "Template can't be edited",
                    "Only a rejected template can be edited and resubmitted; this one is " + t.status() + ".");
        }
        String body = req.body() == null ? null : req.body().strip();
        int paramCount = TemplateRules.checkBody(body, req.examples());
        String header = header(inTenantTx(repo::clinic));
        submitToMeta(() -> {
            client.editTemplate(t.metaTemplateId(), header, body, req.examples());
            return null;
        });
        return inTenantTx(tenantId -> {
            repo.resubmitted(tenantId, id, header, body, paramCount);
            return toResponse(repo.find(tenantId, id).orElseThrow());
        });
    }

    private static String header(WhatsAppTemplateRepository.Clinic clinic) {
        String name = clinic.name().strip();
        return name.length() <= TemplateRules.MAX_HEADER ? name : name.substring(0, TemplateRules.MAX_HEADER).strip();
    }

    private static <T> T submitToMeta(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw metaFailure(e);
        }
    }

    /** True when Meta may have created the template although we saw a failure (timeout, 5xx): keep the row. */
    private static boolean outcomeUnknown(RuntimeException e) {
        return e instanceof ResourceAccessException
                || (e instanceof RestClientResponseException r && r.getStatusCode().is5xxServerError());
    }

    /** Meta's own 4xx verdicts (policy, duplicates, formatting) go back to the clinic verbatim; anything that means
     * "WhatsApp did not answer" (timeout, 5xx, 429, open circuit, bulkhead) is a 502, never a 500. */
    private static RuntimeException metaFailure(RuntimeException e) {
        if (e instanceof RestClientResponseException r && r.getStatusCode().is4xxClientError() && r.getStatusCode().value() != 429) {
            return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "whatsapp-template-refused",
                    "Meta refused the template", metaMessage(r));
        }
        if (e instanceof RestClientResponseException || e instanceof ResourceAccessException
                || e instanceof CircuitOpenException || e instanceof ProviderBusyException) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "whatsapp-unavailable", "WhatsApp is unavailable",
                    "Couldn't reach WhatsApp to submit the template. Try again in a few minutes.");
        }
        return e;
    }

    private static String metaMessage(RestClientResponseException e) {
        try {
            var err = new tools.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString()).path("error");
            String userMsg = err.path("error_user_msg").asString("");
            return userMsg.isBlank() ? err.path("message").asString("Rejected by Meta") : userMsg;
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
