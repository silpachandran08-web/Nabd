package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** IDC-010: the Patient Access Edge creates a family member for a caregiver (path tenant checked by InternalCallerFilter). */
@RestController
class InternalChatMemberController {

    @Schema(name = "ChatMemberGuardianConsent")
    record GuardianConsentDto(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$") String otpChallengeId, @NotBlank @Size(max = 40) String noticeVersion) {
    }

    @Schema(name = "ChatMemberRequest")
    record Request(@NotBlank @Pattern(regexp = "^(child|parent|other)$") String relation,
                   @NotBlank @Size(min = 2, max = 120) String name,
                   @NotNull LocalDate dob,
                   @Size(max = 20) String phone,
                   @Pattern(regexp = "^(male|female|other)$") String gender,
                   @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language,
                   @Valid GuardianConsentDto guardianConsent,
                   @Size(max = 64) String memberRef) {
    }

    @Schema(name = "ChatMemberResponse")
    record Response(String memberRef, String proxyStatus) {
    }

    @Schema(name = "ChatMemberListItem")
    record Item(String memberRef, String firstName, String relation, String proxyStatus, String proxyScope) {
    }

    private final TenantScope tenantScope;
    private final ChatMemberService service;

    InternalChatMemberController(TenantScope tenantScope, ChatMemberService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @PostMapping("/internal/v1/tenants/{tid}/patients/{caregiverRef}/members")
    ResponseEntity<Response> add(@PathVariable UUID tid, @PathVariable String caregiverRef, @Valid @RequestBody Request req) {
        ServiceIdentity edge = allowed(tid);
        ChatMemberService.Result r = tenantScope.run(tid, () -> service.add(caregiverRef, new ChatMemberService.Request(req.relation(), req.name(), req.dob(), req.phone(),
                req.gender(), req.language(), req.guardianConsent() == null ? null
                : new ChatMemberService.GuardianConsent(req.guardianConsent().otpChallengeId(), req.guardianConsent().noticeVersion()), req.memberRef()), edge.serviceId()));
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(new Response(r.memberRef(), r.proxyStatus()));
    }

    @GetMapping("/internal/v1/tenants/{tid}/patients/{caregiverRef}/members")
    Map<String, List<Item>> list(@PathVariable UUID tid, @PathVariable String caregiverRef) {
        allowed(tid);
        return Map.of("members", tenantScope.run(tid, () -> service.members(caregiverRef).stream()
                .map(m -> new Item(m.memberRef(), m.firstName(), m.relation(), m.proxyStatus(), m.proxyScope())).toList()));
    }

    private static ServiceIdentity allowed(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return edge;
    }
}
