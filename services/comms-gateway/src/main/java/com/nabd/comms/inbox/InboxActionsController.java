package com.nabd.comms.inbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * COM-032: the Comms side of the reception actions. Same rules as {@link InboxInternalController}: Core has checked inbox:reply and sends
 * the tenant (X-Tenant-Id) and the acting staff member (X-Staff-Id); Comms never sees a staff JWT. Core's POST/PATCH idempotency
 * key is forwarded as Idempotency-Key and makes a repeated send return the first message.
 */
@RestController
@RequestMapping("/internal/v1/inbox")
@ConditionalOnProperty("comms.field-key")
class InboxActionsController {

    record ReplyRequest(String text, Boolean overrideClinical) {}

    record TemplateRequest(UUID templateId, List<String> params) {}

    record AssignRequest(UUID staffId) {}

    record ModeRequest(String mode) {}

    record NoteRequest(String outcome, String note) {}

    record ClinicalRequest(Boolean clinical) {}

    record CannedRequest(String title, String body, String language) {}

    private final ReplyService replies;
    private final byte[] token;

    InboxActionsController(ReplyService replies, @Value("${comms.internal-token:}") String token) {
        this.replies = replies;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/threads/{id}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    ReplyService.Sent reply(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                            @RequestHeader("X-Staff-Id") UUID staff, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                            @PathVariable UUID id, @RequestBody ReplyRequest b) {
        auth(t);
        return replies.reply(tenant, id, staff, b.text(), Boolean.TRUE.equals(b.overrideClinical()), key);
    }

    @PostMapping("/threads/{id}/template-sends")
    @ResponseStatus(HttpStatus.CREATED)
    ReplyService.Sent template(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                               @RequestHeader("X-Staff-Id") UUID staff, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                               @PathVariable UUID id, @RequestBody TemplateRequest b) {
        auth(t);
        if (b.templateId() == null) {
            throw new InboxException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_SENDABLE");
        }
        return replies.sendTemplate(tenant, id, staff, b.templateId(), b.params(), key);
    }

    @PostMapping("/threads/{id}/assign")
    ReplyService.ThreadState assign(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                    @RequestHeader("X-Staff-Id") UUID staff, @PathVariable UUID id, @RequestBody AssignRequest b) {
        auth(t);
        return replies.assign(tenant, id, staff, b.staffId());
    }

    @PostMapping("/threads/{id}/mode")
    ReplyService.ThreadState mode(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                  @RequestHeader("X-Staff-Id") UUID staff, @PathVariable UUID id, @RequestBody ModeRequest b) {
        auth(t);
        return replies.setMode(tenant, id, staff, b.mode());
    }

    @PostMapping("/threads/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    ReplyService.Note note(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                           @RequestHeader("X-Staff-Id") UUID staff, @PathVariable UUID id, @RequestBody NoteRequest b) {
        auth(t);
        return replies.addNote(tenant, id, staff, b.outcome(), b.note());
    }

    @GetMapping("/threads/{id}/notes")
    List<ReplyService.Note> notes(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                  @PathVariable UUID id) {
        auth(t);
        return replies.notes(tenant, id);
    }

    @PostMapping("/threads/{id}/clinical")
    ReplyService.ThreadState clinical(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                      @RequestHeader("X-Staff-Id") UUID staff, @PathVariable UUID id, @RequestBody ClinicalRequest b) {
        auth(t);
        return replies.tagClinical(tenant, id, staff, Boolean.TRUE.equals(b.clinical()));
    }

    @GetMapping("/canned-replies")
    List<ReplyService.Canned> canned(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                     @RequestParam(required = false) String language) {
        auth(t);
        return replies.canned(tenant, language);
    }

    @PostMapping("/canned-replies")
    @ResponseStatus(HttpStatus.CREATED)
    ReplyService.Canned createCanned(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                     @RequestHeader("X-Staff-Id") UUID staff, @RequestBody CannedRequest b) {
        auth(t);
        return replies.createCanned(tenant, staff, b.title(), b.body(), b.language());
    }

    @GetMapping("/templates")
    List<ReplyService.SendableTemplate> templates(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                                  @RequestParam(required = false) String language) {
        auth(t);
        return replies.sendableTemplates(tenant, language);
    }

    private void auth(String presented) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new InboxException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
