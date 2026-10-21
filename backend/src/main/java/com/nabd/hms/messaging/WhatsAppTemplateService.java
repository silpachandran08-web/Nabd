package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** NB-190 template library. Submission to Meta is synchronous so a rule Meta enforces up front (bad
 * name, duplicate, policy) comes straight back to the clinic; the review verdict arrives later by webhook.
 * OPS-012: create/resubmit are read-check tx, Meta call with no transaction open, write tx — never one
 * transaction around the HTTP call. */
@Service
public class WhatsAppTemplateService {

    /** COM-014: {@code slots} types each {{n}}; null keeps older callers working (only the wording is linted then). */
    public record TemplateRequest(String name, String language, String category, String body, List<String> examples,
                                  List<TemplateLint.Slot> slots) {
        public TemplateRequest(String name, String language, String category, String body, List<String> examples) {
            this(name, language, category, body, examples, null);
        }
    }

    /** COM-014: a platform operator's reviewed override of the PHI lint; the reason and operator go to the audit log. */
    public record PhiOverride(UUID tenantId, UUID operatorId, String ip, String reason) {
    }

    public record TemplateResponse(UUID id, String name, String language, String category, String headerText,
                                   String body, int paramCount, String status, String rejectionReason,
                                   String qualityRating, java.time.Instant updatedAt, String key,
                                   String source) {
    }

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppClient client;
    private final TenantContext tenantContext;
    private final TransactionTemplate tx;
    private final TemplateLint lint;
    private final AuditService audit;
    private final TenantScope tenantScope;

    WhatsAppTemplateService(WhatsAppTemplateRepository repo, WhatsAppClient client, TenantContext tenantContext,
                            PlatformTransactionManager txManager, TemplateLint lint, AuditService audit, TenantScope tenantScope) {
        this.tenantScope = tenantScope;
        this.lint = lint;
        this.audit = audit;
        this.repo = repo;
        this.client = client;
        this.tenantContext = tenantContext;
        this.tx = new TransactionTemplate(txManager);
    }

    /** PLT-019: the tenant is the one bound when the transaction started (the signed-in staff member's). */
    private <T> T inTenantTx(java.util.function.Function<UUID, T> work) {
        return tx.execute(status -> work.apply(tenantContext.current()));
    }

    /** Like inTenantTx, but for a platform operator acting on a named tenant (no tenant claim on the token). */
    private <T> T step(PhiOverride ov, java.util.function.Function<UUID, T> work) {
        return ov == null ? inTenantTx(work) : tenantScope.run(ov.tenantId(), () -> work.apply(ov.tenantId()));
    }

    private ApiException duplicate(TemplateRequest req) {
        return new ApiException(HttpStatus.CONFLICT, "duplicate-whatsapp-template", "Template already exists",
                "A template called " + req.name() + " already exists in " + req.language() + ".");
    }

    public List<TemplateResponse> list() {
        return inTenantTx(tenantId -> repo.list(tenantId).stream().map(WhatsAppTemplateService::toResponse).toList());
    }

    public TemplateResponse create(TemplateRequest req) {
        return create(req, null);
    }

    public TemplateResponse create(TemplateRequest req, PhiOverride override) {
        TemplateRules.checkName(req.name(), req.language());
        List<TemplateLint.Finding> overridden = override == null ? List.of() : lint.lint(req.body(), req.slots());
        if (override == null) {
            lint.enforce(req.body(), req.slots()); // before anything else, so nothing medical reaches Meta
        }
        if (!"UTILITY".equals(req.category()) && !"MARKETING".equals(req.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-whatsapp-template", "Invalid WhatsApp template",
                    "Category must be UTILITY (reminders, updates) or MARKETING (offers, campaigns).");
        }
        String body = req.body() == null ? null : req.body().strip();
        int paramCount = TemplateRules.checkBody(body, req.examples());
        WhatsAppTemplateRepository.Clinic clinic = step(override, tenantId -> {
            if (repo.findByName(tenantId, req.name(), req.language()).isPresent()) {
                throw duplicate(req);
            }
            return repo.clinic(tenantId);
        });
        String header = header(clinic);
        String metaName = clinic.slug().toLowerCase().replaceAll("[^a-z0-9_]", "_") + "_" + req.name();
        WhatsAppClient.Submission submission = submitToMeta(() ->
                client.createTemplate(metaName, req.language(), req.category(), header, body, req.examples()));
        try {
            return step(override, tenantId -> {
                UUID id = repo.insert(tenantId, req.name(), metaName, req.language(), req.category(), header, body, paramCount,
                        statusOf(submission.status()), submission.metaTemplateId());
                if (override != null) {
                    audit.record(tenantId, "operator", override.operatorId(), "Platform operator", "Platform operator", override.ip(),
                            "whatsapp_template.phi_lint_overridden", "whatsapp_template", id, null,
                            Map.of("name", req.name(), "language", req.language(), "reason", override.reason(),
                                    "findings", overridden));
                }
                return toResponse(repo.find(tenantId, id).orElseThrow());
            });
        } catch (DuplicateKeyException raced) { // a concurrent create of the same name won between the check and here
            throw duplicate(req);
        }
    }

    /** The fix path for a rejected template: new text goes back to Meta for review. */
    public TemplateResponse resubmit(UUID id, TemplateRequest req) {
        WhatsAppTemplateRepository.Template t = inTenantTx(tenantId -> repo.find(tenantId, id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "whatsapp-template-not-found", "Template not found", null)));
        if (!"rejected".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "whatsapp-template-not-editable", "Template can't be edited",
                    "Only a rejected template can be edited and resubmitted; this one is " + t.status() + ".");
        }
        lint.enforce(req.body(), req.slots());
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

    static String header(WhatsAppTemplateRepository.Clinic clinic) {
        String name = clinic.name().strip();
        return name.length() <= TemplateRules.MAX_HEADER ? name : name.substring(0, TemplateRules.MAX_HEADER).strip();
    }

    /** Meta's own 4xx verdicts (policy, duplicates, formatting) go back to the clinic verbatim. */
    private static <T> T submitToMeta(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "whatsapp-template-refused",
                        "Meta refused the template", metaMessage(e));
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "whatsapp-unavailable", "WhatsApp is unavailable",
                    "Couldn't reach WhatsApp to submit the template. Try again in a few minutes.");
        }
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
                t.paramCount(), t.status(), t.rejectionReason(), t.qualityRating(), t.updatedAt(),
                t.catalogueKey() != null ? t.catalogueKey() : t.name(), t.catalogueKey() != null ? "catalogue" : "custom");
    }
}
