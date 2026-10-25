package com.nabd.hms.identity.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * IDC-016 app.abdm.*: endpoints only, never credentials (those come from the regional secret store: platform secrets
 * abdm-client-id and abdm-client-secret). hfr-lookup and hpr-lookup are URL templates with {id}, blank until the sandbox
 * contract is confirmed (Needs external action, DEP-05): a blank template means "not configured" (503), never a guess.
 */
@ConfigurationProperties(prefix = "app.abdm")
public record AbdmProperties(String baseUrl, String sessionPath, String cmId, String hfrLookup, String hprLookup, String jwksUri,
                      Duration refreshMargin, Duration defaultTokenLifetime) {

    public AbdmProperties {
        baseUrl = baseUrl == null ? "" : baseUrl;
        sessionPath = sessionPath == null || sessionPath.isBlank() ? "/api/hiecm/gateway/v3/sessions" : sessionPath;
        cmId = cmId == null || cmId.isBlank() ? "sbx" : cmId;
        hfrLookup = hfrLookup == null ? "" : hfrLookup;
        hprLookup = hprLookup == null ? "" : hprLookup;
        jwksUri = jwksUri == null ? "" : jwksUri;
        refreshMargin = refreshMargin == null ? Duration.ofSeconds(60) : refreshMargin;
        defaultTokenLifetime = defaultTokenLifetime == null ? Duration.ofMinutes(10) : defaultTokenLifetime;
    }
}
