package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.setup.dto.SetupDraftRequest;
import com.nabd.hms.organization.setup.dto.SetupDraftResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** ADM-007: a step's in-progress form, kept server-side so a phone and a laptop see the same wizard. Drafts are never written to another module. */
@Service
class SetupDraftService {

    private final SetupRepository repo;
    private final TenantContext tenantContext;

    SetupDraftService(SetupRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    SetupDraftResponse save(String step, SetupDraftRequest req) {
        UUID tenantId = tenantContext.current();
        if (!SetupSteps.ALL.contains(step)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-step", "Invalid wizard step", "Invalid wizard step: " + step);
        }
        if (!req.draft().isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-draft", "Invalid draft", "A draft is a JSON object.");
        }
        String json = req.draft().toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > SetupSteps.MAX_DRAFT_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "draft-too-large", "Draft too large", "A step draft is limited to 16 KB.");
        }
        int version = repo.saveDraft(tenantId, step, json, req.version()).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "version-conflict", "VERSION_CONFLICT", "This step was saved from another device. Reload to see the latest."));
        return new SetupDraftResponse(step, version);
    }
}
