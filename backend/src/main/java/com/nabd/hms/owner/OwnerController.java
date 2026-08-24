package com.nabd.hms.owner;

import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.owner.dto.OwnerLoginRequest;
import com.nabd.hms.owner.dto.PendingWorkspaceTokenResponse;
import com.nabd.hms.owner.dto.WorkspaceSelectRequest;
import com.nabd.hms.owner.dto.WorkspacesResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owners")
public class OwnerController {

    private final OwnerService service;

    OwnerController(OwnerService service) {
        this.service = service;
    }

    @PostMapping("/auth/login")
    public PendingWorkspaceTokenResponse login(@Valid @RequestBody OwnerLoginRequest req, HttpServletRequest http) {
        return service.login(req, RequestMeta.clientIp(http));
    }

    @GetMapping("/me/workspaces")
    public WorkspacesResponse workspaces(@AuthenticationPrincipal Jwt jwt) {
        return service.listWorkspaces(jwt);
    }

    @PostMapping("/workspaces/select")
    public TokenPairResponse selectWorkspace(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WorkspaceSelectRequest req,
                                              HttpServletRequest http) {
        return service.selectWorkspace(jwt, req, RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }
}
