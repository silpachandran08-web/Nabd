package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.api.RecordItemSource;
import com.nabd.hms.patient.securelink.SecureLinkService;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-050: 'My records' for the Patient Access Edge (service identity, tenant in the path like every Edge call, so the mTLS tenant
 * allow-list and the idempotency scope apply). The Edge asserts the verified phone as its SHA-256; who that phone may see is decided
 * here (RecordAccess), never by the Edge. A phone that needs re-verification (COM-041) gets 403 PHONE_REVERIFY_REQUIRED on every
 * call. Answers carry a reference, a type and a date only: no drug, test, diagnosis, amount or document name. Every list and every
 * link is audited with the patient, the actor's phone hash, the item and the channel.
 */
@RestController
class InternalRecordsController {

    /** First character of an item reference, and what secure_links.document_type calls the type. */
    private static final Map<String, String[]> TYPES = Map.of("prescription", new String[]{"p", "rx"}, "report", new String[]{"r", "report"},
            "invoice", new String[]{"i", "invoice"});
    private static final String CHANNEL = "^[a-z_]{2,20}$";
    static final int DEFAULT_TTL_MINUTES = 60;

    @Schema(name = "InternalRecordSubject")
    record SubjectView(String patientRef, String firstName, String relation) {
    }

    @Schema(name = "InternalRecordSubjects")
    record Subjects(List<SubjectView> subjects) {
    }

    @Schema(name = "InternalRecordItem")
    record ItemView(String itemRef, String type, LocalDate date) {
    }

    @Schema(name = "InternalRecordItems")
    record Items(List<ItemView> items) {
    }

    @Schema(name = "InternalRecordLinkRequest")
    record LinkRequest(@NotBlank @Pattern(regexp = "^[pri][0-9a-f]{32}$") String itemRef, @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String recipientPhoneHash,
                       @Min(5) @Max(1440) Integer ttlMinutes, @Pattern(regexp = CHANNEL) String channel) {
    }

    @Schema(name = "InternalRecordLink")
    record LinkResponse(String linkUrl, Instant expiresAt, String type, LocalDate date) {
    }

    private record Listed(String type, RecordItemSource.Item item) {
    }

    private final TenantScope tenantScope;
    private final PatientRefs refs;
    private final RecordAccess access;
    private final PhoneReverificationService reverifications;
    private final List<RecordItemSource> sources;
    private final SecureLinkService links;
    private final AuditService audit;
    private final ClinicClock clock;

    InternalRecordsController(TenantScope tenantScope, PatientRefs refs, RecordAccess access, PhoneReverificationService reverifications,
                              List<RecordItemSource> sources, SecureLinkService links, AuditService audit, ClinicClock clock) {
        this.tenantScope = tenantScope;
        this.refs = refs;
        this.access = access;
        this.reverifications = reverifications;
        this.sources = sources.stream().filter(s -> TYPES.containsKey(s.type())).toList();
        this.links = links;
        this.audit = audit;
        this.clock = clock;
    }

    /** Whose records this phone may open (first names for the one-tap switch); empty when it may open nobody's. */
    @GetMapping("/internal/v1/tenants/{tid}/households/{phoneHash}/record-subjects")
    Subjects subjects(@PathVariable UUID tid, @PathVariable String phoneHash) {
        caller(tid, phoneHash);
        return tenantScope.run(tid, () -> {
            verified(tid, phoneHash);
            return new Subjects(access.subjects(phoneHash).stream().map(s -> new SubjectView(s.patientRef(), s.firstName(), s.relation())).toList());
        });
    }

    /** The patient's newest items of every type, at most 10. 403 NOT_IN_PROXY_SCOPE for a patient the phone may not see, known or not. */
    @GetMapping("/internal/v1/tenants/{tid}/patients/{patientRef}/record-items")
    Items items(@PathVariable UUID tid, @PathVariable String patientRef, @RequestParam String phoneHash,
                @RequestParam(defaultValue = "10") int limit, @RequestParam(defaultValue = "whatsapp") String channel) {
        ServiceIdentity edge = caller(tid, phoneHash);
        channel(channel);
        int n = Math.max(1, Math.min(10, limit));
        return tenantScope.run(tid, () -> {
            verified(tid, phoneHash);
            UUID subject = refs.patientIdFor(patientRef).filter(p -> access.viewer(phoneHash, p).isPresent()).orElseThrow(() ->
                    new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_PROXY_SCOPE", "Not in proxy scope", "This number may not see that person's records."));
            ZoneId zone = clock.zone(tid);
            List<ItemView> items = sources.stream().flatMap(s -> s.latest(subject, n).stream().map(i -> new Listed(s.type(), i)))
                    .sorted(Comparator.comparing((Listed l) -> l.item().at()).reversed().thenComparing(l -> l.item().id())).limit(n)
                    .map(l -> new ItemView(TYPES.get(l.type())[0] + l.item().id().toString().replace("-", ""), l.type(), l.item().at().atZone(zone).toLocalDate()))
                    .toList();
            audit(tid, edge, "records.listed", subject, phoneHash, channel, Map.of("itemRefs", items.stream().map(ItemView::itemRef).toList()));
            return new Items(items);
        });
    }

    /**
     * An OTP-protected link (IDC-015) to one item, addressed to the patient on the asserting phone. The item names its own patient,
     * so the scope is checked against the item, not against anything the Edge says. 404 for an item that does not exist, is revoked or
     * void, or belongs to someone the phone may not see (no difference, so a reference cannot be probed).
     */
    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/secure-links")
    @ResponseStatus(HttpStatus.CREATED)
    LinkResponse link(@PathVariable UUID tid, @Valid @RequestBody LinkRequest r) {
        ServiceIdentity edge = caller(tid, r.recipientPhoneHash());
        String channel = r.channel() == null ? "whatsapp" : r.channel();
        return tenantScope.run(tid, () -> {
            verified(tid, r.recipientPhoneHash());
            RecordItemSource source = sources.stream().filter(s -> TYPES.get(s.type())[0].equals(r.itemRef().substring(0, 1))).findFirst().orElseThrow(ApiException::notFound);
            UUID itemId = UUID.fromString(r.itemRef().substring(1).replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
            RecordItemSource.Found found = source.find(itemId).orElseThrow(ApiException::notFound);
            UUID recipient = access.viewer(r.recipientPhoneHash(), found.patientId()).orElseThrow(ApiException::notFound);
            SecureLinkService.ChatLink link = links.issueFromChat(TYPES.get(source.type())[1], source.file(itemId), found.patientId(), recipient,
                    Duration.ofMinutes(r.ttlMinutes() == null ? DEFAULT_TTL_MINUTES : r.ttlMinutes())).orElseThrow(ApiException::notFound);
            audit(tid, edge, "records.link_issued", found.patientId(), r.recipientPhoneHash(), channel,
                    Map.of("itemRef", r.itemRef(), "linkId", link.linkId().toString(), "recipientPatientId", recipient.toString()));
            return new LinkResponse(link.url(), link.expiresAt(), source.type(), found.at().atZone(clock.zone(tid)).toLocalDate());
        });
    }

    private void audit(UUID tid, ServiceIdentity edge, String action, UUID patient, String phoneHash, String channel, Map<String, Object> more) {
        Map<String, Object> details = new LinkedHashMap<>(more);
        details.put("actorPhoneHash", phoneHash);
        details.put("channel", channel);
        audit.record(tid, "system", null, "patient via edge", edge.serviceId(), null, action, "patient", patient, null, details);
    }

    /** COM-041: nothing from the records side is served on a number that is flagged or locked. */
    private void verified(UUID tid, String phoneHash) {
        if (!"verified".equals(reverifications.state(tid, phoneHash))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PHONE_REVERIFY_REQUIRED", "Number not verified",
                    "This number must be verified again in the chat before records are shown.");
        }
    }

    private static void channel(String channel) {
        if (!channel.matches(CHANNEL)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "channel must be a lower-case word.");
        }
    }

    private static ServiceIdentity caller(UUID tid, String phoneHash) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        if (phoneHash == null || !phoneHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "phoneHash must be 64 lowercase hex characters (SHA-256).");
        }
        return edge;
    }
}
