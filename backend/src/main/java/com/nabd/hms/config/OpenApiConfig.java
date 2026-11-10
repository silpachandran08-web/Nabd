package com.nabd.hms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPS-034: shapes the springdoc output (only built when springdoc.api-docs.enabled=true, i.e. in OpenApiSpecApiTest).
 * Every operation gets: tag = module (package under com.nabd.hms), operationId, shared Problem+JSON errors and x-required-permission.
 */
@Configuration
@ConditionalOnProperty("springdoc.api-docs.enabled")
public class OpenApiConfig {

    /** Mirrors the permitAll matchers in SecurityConfig. shortcut: copied by hand, keep both lists together; read them from one place if a third copy is ever needed. */
    static final List<String> PUBLIC = List.of("/v1/auth/login", "/v1/auth/otp/request", "/v1/auth/otp/verify",
            "/v1/auth/mfa/verify", "/v1/auth/mfa/recovery-verify", "/v1/auth/pin/reset-request", "/v1/auth/pin/reset-confirm", "/v1/auth/refresh",
            "/v1/auth/mfa/enroll", "/v1/auth/mfa/confirm", "/v1/owners/me/workspaces", "/v1/owners/workspaces/select",
            "/v1/staff/invitations/*/accept", "/v1/owners/auth/login", "/v1/owners/invitations/*/accept",
            "/v1/owners/auth/mfa/*", "/v1/platform/auth/mfa/enroll", "/v1/platform/auth/mfa/confirm", // PLT-005: challenge/setup token in the body
            "/v1/platform/auth/login", "/v1/platform/auth/mfa/verify", "/v1/platform/auth/refresh",
            "/.well-known/jwks.json", "/.well-known/jwks/*.json", "/v1/webhooks/whatsapp", "/v1/billing/gateway-webhooks/*/*",
            "/internal/v1/notification-requests/*", // COM-004: service token, not a bearer
            "/internal/v1/consents:export", // COM-008: same service token
            "/v1/plans/catalogue", // ADM-034: public plan comparison
            "/v1/public/notices/*/*", "/v1/proxy-invites/*", "/v1/proxy-invites/*/otp", "/v1/proxy-invites/*/accept", "/v1/me/profile-claims/*", "/v1/me/profile-claims/*/otp", "/v1/public/links/*/otp", "/v1/public/links/*/open", "/v1/public/trials", "/v1/public/trials/*/verify", "/v1/public/trials/*/status", "/abdm/v3/callbacks/**", "/abdm/v3/hip/patient/share", "/v1/display/pair", "/v1/display/stream", "/files-blob/object"); // PLT-029 signed object URLs (FilesBlobSecurityConfig); ACC-023 lobby TV (own device token), ADM-003 trials, IDC-003 public page, IDC-016 gateway-signed callbacks (PublicEndpointsSecurityConfig)

    private static final Pattern AUTHORITY = Pattern.compile("'([^']+)'");
    /** OPS-035: only a plain OR-chain of hasAuthority/hasAnyAuthority becomes "a | b"; any other SpEL is written verbatim. */
    private static final Pattern OR_CHAIN = Pattern.compile("has(Any)?Authority\\('[^']+'(\\s*,\\s*'[^']+')*\\)(\\s+or\\s+has(Any)?Authority\\('[^']+'(\\s*,\\s*'[^']+')*\\))*");
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    static { // PLT-032: swagger-core only knows Jackson 2's JsonNode; without this a Jackson 3 JsonNode is documented as a bean
        org.springdoc.core.utils.SpringDocUtils.getConfig().replaceWithClass(tools.jackson.databind.JsonNode.class, com.fasterxml.jackson.databind.JsonNode.class);
    }

    @Bean
    OpenAPI nabdOpenApi() {
        Schema<?> str = new Schema<>().type("string");
        Schema<?> problem = new Schema<>().type("object").description("RFC 7807 problem+json (GlobalExceptionHandler)")
                .addProperty("type", str).addProperty("title", str).addProperty("status", new Schema<>().type("integer"))
                .addProperty("detail", str).addProperty("instance", str).addProperty("traceId", str)
                .addProperty("code", new Schema<>().type("string").description("Stable machine code on 503s"))
                .addProperty("errors", new Schema<>().type("array").items(new Schema<>().type("object")
                        .addProperty("field", str).addProperty("message", str)));
        return new OpenAPI()
                .servers(List.of(new Server().url("/"))) // fixed, else springdoc writes the request host into the committed file
                .info(new Info().title("Nabd HMS Core API").version("1.0")
                        .description("Generated from the controllers (OPS-034). Do not edit by hand: run OpenApiSpecApiTest with -Dopenapi.write=true."))
                .components(new Components().addSchemas("Problem", problem)
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OperationCustomizer nabdOperationCustomizer() {
        return (operation, handler) -> {
            Class<?> type = handler.getBeanType();
            String pkg = type.getPackageName().replaceFirst("^com\\.nabd\\.hms\\.?", "");
            String module = pkg.isEmpty() ? "core" : pkg.split("\\.")[0];
            operation.setTags(List.of(module));
            operation.setOperationId(type.getSimpleName() + "_" + handler.getMethod().getName());

            PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (pre == null) pre = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            String permission;
            if (pre != null) {
                Set<String> authorities = new LinkedHashSet<>();
                Matcher m = AUTHORITY.matcher(pre.value());
                if (OR_CHAIN.matcher(pre.value().trim()).matches()) while (m.find()) authorities.add(m.group(1));
                permission = authorities.isEmpty() ? pre.value() : String.join(" | ", authorities);
            } else if (isPublic(type, handler.getMethod())) {
                permission = "public";
            } else {
                permission = "authenticated";
            }
            operation.addExtension("x-required-permission", permission);

            Schema<?> ref = new Schema<>().$ref("#/components/schemas/Problem");
            Content problem = new Content().addMediaType("application/problem+json", new MediaType().schema(ref));
            if (operation.getResponses() == null) operation.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
            operation.getResponses().addApiResponse("default", new ApiResponse().description("Error (RFC 7807)").content(problem));
            if (!"public".equals(permission)) {
                operation.getResponses().addApiResponse("401", new ApiResponse().description("Not authenticated").content(problem));
                if (!"authenticated".equals(permission))
                    operation.getResponses().addApiResponse("403", new ApiResponse().description("Permission missing").content(problem));
            }
            return operation;
        };
    }

    static boolean isPublic(Class<?> type, java.lang.reflect.Method method) {
        RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        RequestMapping own = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        List<String> bases = base == null || base.path().length == 0 ? List.of("") : List.of(base.path());
        List<String> own2 = own == null || own.path().length == 0 ? List.of("") : List.of(own.path());
        List<String> full = new ArrayList<>();
        for (String b : bases) for (String o : own2) full.add(b + o);
        return full.stream().anyMatch(p -> PUBLIC.stream().anyMatch(pub -> MATCHER.match(pub, p.replaceAll("\\{[^}]+}", "x"))));
    }
}
