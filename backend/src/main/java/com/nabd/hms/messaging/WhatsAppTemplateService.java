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
import com.nabd.hms.shared.integration.CircuitOpenException;
import org.springframework.web.client.ResourceAccessException;
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

    /** COM-013: {@code category} is the effective one (Meta's after a re-categorisation, see {@code categoryChangedAt});
     * {@code versions} is the history of this template, newest first; {@code activeVersion} is the one sends use. */
    public record TemplateResponse(UUID id, String name, String language, String category, String headerText,
                                   String body, int paramCount, String status, String rejectionReason,
                                   String qualityRating, java.time.Instant updatedAt, String key,
                                   String source, int version, boolean active, int activeVersion, String requestedCategory,
                                   java.time.Instant categoryChangedAt, java.time.Instant pausedUntil, List<VersionInfo> versions) {
    }

    public record VersionInfo(UUID id, int version, String status, String rejectionReason, boolean active, String headerText,
                              String body, java.time.Instant createdAt) {
    }

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppClient client;
    private final TenantContext tenantContext;
    private final TransactionTemplate tx;
    private final TemplateLint lint;
    private final AuditService audit;
    private final TenantScope tenantScope;
    private final TemplateLifecycle lifecycle;

    WhatsAppTemplateService(WhatsAppTemplateRepository repo, WhatsAppClient client, TenantContext tenantContext,
                            PlatformTransactionManager txManager, TemplateLint lint, AuditService audit, TenantScope tenantScope,
                            TemplateLifecycle lifecycle) {
        this.tenantScope = tenantScope;
        this.lifecycle = lifecycle;
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

    /** One entry per template (its active version), each with its version history. */
    public List<TemplateResponse> list() {
        return inTenantTx(tenantId -> {
            Map<String, List<WhatsAppTemplateRepository.Template>> groups = new java.util.LinkedHashMap<>();
            repo.list(tenantId).forEach(t -> groups.computeIfAbsent(t.name() + "\u0000" + t.language(), k -> new java.util.ArrayList<>()).add(t));
            return groups.values().stream().map(g -> toResponse(g.stream().filter(WhatsAppTemplateRepository.Template::active)
                    .findFirst().orElse(g.get(0)), g)).toList();
        });
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
        // COM-003 two-phase: (1) the 'submitting' row is committed first, (2) Meta is called with no transaction open,
        // (3) the row is completed. A timeout or a failed write after Meta accepted leaves the row for TemplateReconciler.
        String[] names = new String[2]; // header, metaName
        UUID rowId;
        try {
            rowId = step(override, tenantId -> {
                WhatsAppTemplateRepository.Clinic clinic = repo.clinic(tenantId);
                names[0] = header(clinic);
                names[1] = clinic.slug().toLowerCase().replaceAll("[^a-z0-9_]", "_") + "_" + req.name();
                return repo.insertSubmitting(tenantId, req.name(), names[1], req.language(), req.category(), names[0], body, paramCount,
                        req.examples());
            });
        } catch (DuplicateKeyException exists) { // same name+language (or Meta name) already taken, or a concurrent create won
            throw duplicate(req);
        }
        WhatsAppClient.Submission submission;
        try {
            submission = client.createTemplate(names[1], req.language(), req.category(), names[0], body, req.examples());
        } catch (RuntimeException e) {
            if (!outcomeUnknown(e)) { // Meta never created it: free the name at once
                step(override, tenantId -> repo.deleteSubmitting(tenantId, rowId));
            }
            throw metaFailure(e);
        }
        return step(override, tenantId -> {
            repo.completeSubmission(tenantId, rowId, statusOf(submission.status()), submission.metaTemplateId());
            if (override != null) {
                audit.record(tenantId, "operator", override.operatorId(), "Platform operator", "Platform operator", override.ip(),
                        "whatsapp_template.phi_lint_overridden", "whatsapp_template", rowId, null,
                        Map.of("name", req.name(), "language", req.language(), "reason", override.reason(),
                                "findings", overridden));
            }
            return response(tenantId, rowId);
        });
    }

    /** Called inside the caller's tenant transaction (the nested execute joins it). */
    private TemplateResponse response(UUID tenantId, UUID id) {
        return tx.execute(status -> {
            WhatsAppTemplateRepository.Template t = repo.find(tenantId, id).orElseThrow();
            return toResponse(t, repo.list(tenantId).stream().filter(g -> g.name().equals(t.name()) && g.language().equals(t.language())).toList());
        });
    }

    private record Prepared(UUID id, String metaName, String category, String header, TemplateResponse replay) {
    }

    private static ApiException inReview() {
        return new ApiException(HttpStatus.CONFLICT, "version-in-review", "A version is already in review",
                "Meta is still reviewing the last change to this template. Wait for its answer, then edit again.",
                Map.of("code", "VERSION_IN_REVIEW"));
    }

    /** COM-013: an edit is a NEW version submitted to Meta under its own name, whatever the current status; the active version
     * keeps sending until Meta approves the new one (then it takes over at once). Sending the same text again while that
     * version is in review returns it (idempotent); different text gets 409 VERSION_IN_REVIEW. The slots must stay the same so
     * every caller keeps working. Same three phases as create (OPS-012). */
    public TemplateResponse resubmit(UUID id, TemplateRequest req) {
        lint.enforce(req.body(), req.slots());
        String body = req.body() == null ? null : req.body().strip();
        int paramCount = TemplateRules.checkBody(body, req.examples());
        Prepared p;
        try {
            p = inTenantTx(tenantId -> {
                WhatsAppTemplateRepository.Template t = repo.find(tenantId, id).orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "whatsapp-template-not-found", "Template not found", null));
                List<WhatsAppTemplateRepository.Template> group = repo.groupForUpdate(tenantId, t.name(), t.language());
                WhatsAppTemplateRepository.Template active = group.stream().filter(WhatsAppTemplateRepository.Template::active)
                        .findFirst().orElse(group.get(0));
                var review = group.stream().filter(WhatsAppTemplateRepository.Template::inReview).findFirst();
                if (review.isPresent()) {
                    if (review.get().body().equals(body)) {
                        return new Prepared(null, null, null, null, toResponse(review.get(), group));
                    }
                    throw inReview();
                }
                if (paramCount != active.paramCount()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-whatsapp-template", "Invalid WhatsApp template",
                            "A new version must keep the same " + active.paramCount() + " {{n}} slots, so the messages that use it keep working.");
                }
                int next = group.get(0).version() + 1;
                String metaName = group.get(group.size() - 1).metaName() + "_v" + next;
                String header = header(repo.clinic(tenantId));
                UUID newId = repo.insertVersion(tenantId, active, next, metaName, header, body, req.examples(), "submitting");
                return new Prepared(newId, metaName, active.effectiveCategory(), header, null);
            });
        } catch (DuplicateKeyException concurrent) { // another edit (or a rename resubmission) took this version number first
            throw inReview();
        }
        if (p.replay() != null) {
            return p.replay();
        }
        WhatsAppClient.Submission submission;
        try {
            WhatsAppTemplateRepository.Template t = inTenantTx(tenantId -> repo.find(tenantId, p.id()).orElseThrow());
            submission = client.createTemplate(p.metaName(), t.language(), p.category(), p.header(), body, req.examples());
        } catch (RuntimeException e) {
            if (!outcomeUnknown(e)) {
                inTenantTx(tenantId -> repo.deleteSubmitting(tenantId, p.id()));
            }
            throw metaFailure(e);
        }
        return inTenantTx(tenantId -> {
            repo.completeSubmission(tenantId, p.id(), statusOf(submission.status()), submission.metaTemplateId());
            lifecycle.activateIfNewer(tenantId, p.id()); // Meta may approve at once
            return response(tenantId, p.id());
        });
    }

    /** COM-013 clinic rename: every active template whose header is not the clinic's name any more gets a new version with the
     * new header, queued for CatalogueSubmissionJob (Meta is never called here). A version Meta has not seen yet takes the new
     * header in place. Cross-tenant; one clinic failing never stops the others. */
    int resyncHeaders(int limit) {
        int n = 0;
        for (WhatsAppTemplateRepository.Ref ref : repo.staleHeaders(limit)) {
            try {
                UUID tenantId = ref.tenantId();
                n += tenantScope.run(tenantId, () -> {
                    WhatsAppTemplateRepository.Template t = repo.find(tenantId, ref.id()).orElse(null);
                    if (t == null || !t.active()) {
                        return 0;
                    }
                    String header = header(repo.clinic(tenantId));
                    List<WhatsAppTemplateRepository.Template> group = repo.groupForUpdate(tenantId, t.name(), t.language());
                    var queued = group.stream().filter(v -> "queued".equals(v.status()) && v.version() >= t.version()).findFirst();
                    if (queued.isPresent()) {
                        repo.setHeader(tenantId, queued.get().id(), header);
                        return 1;
                    }
                    int next = group.get(0).version() + 1;
                    repo.insertVersion(tenantId, t, next, group.get(group.size() - 1).metaName() + "_v" + next, header, t.body(), t.examples(), "queued");
                    return 1;
                });
            } catch (DuplicateKeyException race) {
                // an edit took the version number at the same moment: the next run sees its header and decides again
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(WhatsAppTemplateService.class)
                        .warn("header resubmission failed for template {} (tenant {}); the next run retries", ref.id(), ref.tenantId(), e);
            }
        }
        return n;
    }

    static String header(WhatsAppTemplateRepository.Clinic clinic) {
        String name = clinic.name().strip();
        return name.length() <= TemplateRules.MAX_HEADER ? name : name.substring(0, TemplateRules.MAX_HEADER).strip();
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

    private static TemplateResponse toResponse(WhatsAppTemplateRepository.Template t, List<WhatsAppTemplateRepository.Template> group) {
        int activeVersion = group.stream().filter(WhatsAppTemplateRepository.Template::active).mapToInt(WhatsAppTemplateRepository.Template::version)
                .findFirst().orElse(t.version());
        return new TemplateResponse(t.id(), t.name(), t.language(), t.effectiveCategory(), t.headerText(), t.body(),
                t.paramCount(), t.status(), t.rejectionReason(), t.qualityRating(), t.updatedAt(),
                t.catalogueKey() != null ? t.catalogueKey() : t.name(), t.catalogueKey() != null ? "catalogue" : "custom",
                t.version(), t.active(), activeVersion, t.category(), t.categoryChangedAt(), t.pausedUntil(),
                group.stream().map(v -> new VersionInfo(v.id(), v.version(), v.status(), v.rejectionReason(), v.active(),
                        v.headerText(), v.body(), v.createdAt())).toList());
    }
}
