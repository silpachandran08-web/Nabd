package com.nabd.hms.clinical;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLN-005: a break-glass session (token with the `bg` claim) can never sign a prescription or issue a clinical document.
 * Runs before PLT-012's generic read-only block so the refusal is specific (403 break-glass-cannot-sign) and leaves an
 * audit row; the audit commits on its own because no business transaction is open yet.
 */
@Configuration
class ClinicalBreakGlassSignGuard implements WebMvcConfigurer, HandlerInterceptor {

    private static final Pattern SIGN = Pattern.compile(".*/v1/clinical/(prescriptions/([^/]+)/sign|documents/([^/]+)/issue)/?");

    private final AuditService auditService;

    ClinicalBreakGlassSignGuard(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/clinical/**").order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())
                || !(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t)
                || !t.getToken().hasClaim("bg")) {
            return true;
        }
        Matcher m = SIGN.matcher(request.getRequestURI());
        if (!m.matches()) {
            return true;
        }
        boolean rx = m.group(2) != null;
        String what = rx ? "sign a prescription" : "issue a clinical document";
        UUID entityId = parse(rx ? m.group(2) : m.group(3));
        if (entityId != null) { // a malformed id is refused all the same, without an audit row to attach it to
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("reason", ClinicalAuthority.BREAK_GLASS);
            after.put("breakGlassGrantId", t.getToken().getClaimAsString("bg"));
            after.put(rx ? "queueEntryId" : "documentId", entityId.toString());
            auditService.record(UUID.fromString(t.getToken().getClaimAsString("tenantId")), "staff",
                    UUID.fromString(t.getToken().getSubject()), String.valueOf(t.getToken().getClaimAsString("staffName")),
                    String.valueOf(t.getToken().getClaimAsString("roleName")), RequestMeta.clientIp(request),
                    rx ? "prescription.sign_refused" : "clinical_document.issue_refused",
                    rx ? "queue_entry" : "clinical_document", entityId, null, after);
        }
        throw ClinicalAuthority.forbidden(ClinicalAuthority.BREAK_GLASS, what);
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
