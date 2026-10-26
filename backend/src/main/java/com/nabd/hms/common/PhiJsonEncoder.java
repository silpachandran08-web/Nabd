package com.nabd.hms.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One JSON object per line for the prod profile (logback-spring.xml). traceId is the requestId MDC value
 * that GlobalExceptionHandler returns in error bodies. Message and stack trace are masked by PhiMasker. */
public class PhiJsonEncoder extends EncoderBase<ILoggingEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.ofEpochMilli(e.getTimeStamp()).toString());
        m.put("level", e.getLevel().toString());
        m.put("logger", e.getLoggerName());
        m.put("thread", e.getThreadName());
        m.put("traceId", e.getMDCPropertyMap().get(RequestCorrelationFilter.MDC_KEY));
        m.put("tenantId", uuidOrNull(claimOrMdc(e, "tenantId")));
        m.put("staffId", uuidOrNull(claimOrMdc(e, "staffId")));
        m.put("reasonCode", e.getMDCPropertyMap().get("reasonCode"));
        m.put("message", PhiMasker.mask(e.getFormattedMessage()));
        if (e.getThrowableProxy() != null) {
            m.put("stack_trace", PhiMasker.mask(ThrowableProxyUtil.asString(e.getThrowableProxy())));
        }
        try {
            return (JSON.writeValueAsString(m) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return ("{\"message\":\"log encoding failed\"}\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    /** MDC wins; otherwise the authenticated JWT of the logging thread (tenantId claim, subject = staff id). */
    private static String claimOrMdc(ILoggingEvent e, String key) {
        String v = e.getMDCPropertyMap().get(key);
        if (v != null) {
            return v;
        }
        if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            return key.equals("staffId") ? jwt.getToken().getSubject() : jwt.getToken().getClaimAsString(key);
        }
        return null;
    }

    private static String uuidOrNull(String v) {
        try {
            return v == null ? null : java.util.UUID.fromString(v).toString();
        } catch (IllegalArgumentException ex) {
            return null; // never print a non-uuid identity value
        }
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }
}
