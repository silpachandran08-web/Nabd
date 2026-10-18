package com.nabd.hms.owner;

import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.owner.dto.AcceptOwnerInviteRequest;
import com.nabd.hms.owner.dto.OwnerLoginRequest;
import com.nabd.hms.owner.dto.PendingWorkspaceTokenResponse;
import com.nabd.hms.owner.dto.WorkspaceSelectRequest;
import com.nabd.hms.owner.dto.WorkspacesResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owners")
public class OwnerController {

    private final OwnerService service;

    private final ChallengeTokenDecoder challengeDecoder;

    OwnerController(OwnerService service, ChallengeTokenDecoder challengeDecoder) {
        this.service = service;
        this.challengeDecoder = challengeDecoder;
    }

    @PostMapping("/auth/login")
    public PendingWorkspaceTokenResponse login(@Valid @RequestBody OwnerLoginRequest req, HttpServletRequest http) {
        return service.login(req, RequestMeta.clientIp(http));
    }

    /** Public, like StaffController.acceptInvite — the raw token in the URL is the credential. */
    @PostMapping("/invitations/{token}/accept")
    public PendingWorkspaceTokenResponse acceptInvite(@PathVariable String token, @Valid @RequestBody AcceptOwnerInviteRequest req) {
        return service.acceptInvite(token, req.pin());
    }

    @GetMapping("/me/workspaces")
    public WorkspacesResponse workspaces(@RequestHeader(value = "Authorization", required = false) String auth) {
        return service.listWorkspaces(pending(auth));
    }

    @PostMapping("/workspaces/select")
    public TokenPairResponse selectWorkspace(@RequestHeader(value = "Authorization", required = false) String auth, @Valid @RequestBody WorkspaceSelectRequest req,
                                              HttpServletRequest http) {
        return service.selectWorkspace(pending(auth), req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    /** PLT-001: the owner_select token is decoded here, never by the resource server. */
    private Jwt pending(String auth) {
        try {
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return challengeDecoder.decode(auth.substring(7).trim(), TokenType.OWNER_SELECT);
            }
        } catch (JwtException e) {
            // fall through to the same 401 as a missing token
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid-workspace-token", "Invalid workspace token",
                "This action requires a valid pending-workspace token from /v1/owners/auth/login.");
    }
}
