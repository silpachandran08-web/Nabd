package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.RequestCorrelationFilter;
import com.nabd.hms.common.RequestMeta;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PLT-039: captures a {@link PhiRead} response on the request thread (in-memory only, no I/O) and hands it to
 * {@link PhiReadRecorder}, which writes it off the request path. Error responses never get here with an annotated
 * method: GlobalExceptionHandler's handler is the one writing the body, so 4xx/5xx record nothing.
 * shortcut: a serialization failure after this point (500) still records the read; switch to an interceptor's
 * afterCompletion if that ever matters.
 */
@ControllerAdvice
class PhiReadCapture implements ResponseBodyAdvice<Object> {

    private final PhiReadRecorder recorder;
    private final Timer captureTimer;

    private final java.time.Clock clock;

    PhiReadCapture(PhiReadRecorder recorder, MeterRegistry meters, java.time.Clock clock) {
        this.clock = clock;
        this.recorder = recorder;
        this.captureTimer = Timer.builder("nabd.phi_read.capture").description("PHI read capture time on the request thread")
                .publishPercentiles(0.95).register(meters);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(PhiRead.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest req && response instanceof ServletServerHttpResponse res) {
            int status = res.getServletResponse().getStatus();
            if (status >= 200 && status < 300) {
                captureTimer.record(() -> capture(body, returnType.getMethodAnnotation(PhiRead.class), req.getServletRequest()));
            }
        }
        return body;
    }

    private void capture(Object body, PhiRead ann, HttpServletRequest http) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof Jwt jwt) || jwt.getClaimAsString("tenantId") == null) {
            return; // shortcut: staff sessions only; operator/patient-link actors are recorded when those surfaces annotate
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) http.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        vars = vars == null ? Map.of() : vars;
        UUID pathPatient = uuid(vars.get("patientId"));
        Set<UUID> patients = pathPatient != null ? Set.of(pathPatient) : patientIds(body, ann.key());
        UUID queueEntryId = uuid(vars.get("queueEntryId"));
        if (patients.isEmpty() && queueEntryId == null) {
            return;
        }
        UUID bg = uuid(jwt.getClaimAsString("bg"));
        List<String> perms = jwt.getClaimAsStringList("permissions");
        recorder.submit(new PhiReadRecorder.Read(
                UUID.fromString(jwt.getClaimAsString("tenantId")), uuid(jwt.getSubject()), uuid(jwt.getClaimAsString("sid")),
                perms != null && perms.contains("clinical:edit"), bg, bg != null ? "break_glass" : ann.purpose(), ann.value(),
                queueEntryId != null ? queueEntryId : uuid(vars.get("id")),
                (String) http.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), RequestMeta.clientIp(http),
                MDC.get(RequestCorrelationFilter.MDC_KEY), List.copyOf(patients), patients.isEmpty() ? queueEntryId : null,
                ann.value().endsWith("_list"), java.time.Instant.now(clock).toEpochMilli()));
    }

    /** The body's own {key} component, else that component of each element of the body (or of its first list component). */
    static Set<UUID> patientIds(Object body, String key) {
        Set<UUID> out = new LinkedHashSet<>();
        UUID own = component(body, key);
        if (own != null) {
            out.add(own);
            return out;
        }
        Iterable<?> items = body instanceof Iterable<?> it ? it : firstList(body);
        if (items != null) {
            for (Object item : items) {
                UUID id = component(item, key);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static UUID component(Object o, String name) {
        if (o == null || !o.getClass().isRecord()) {
            return null;
        }
        for (RecordComponent c : o.getClass().getRecordComponents()) {
            if (c.getName().equals(name) && c.getType() == UUID.class) {
                return (UUID) read(c, o);
            }
        }
        return null;
    }

    private static Iterable<?> firstList(Object o) {
        if (o == null || !o.getClass().isRecord()) {
            return null;
        }
        for (RecordComponent c : o.getClass().getRecordComponents()) {
            if (List.class.isAssignableFrom(c.getType())) {
                return (List<?>) read(c, o);
            }
        }
        return null;
    }

    private static Object read(RecordComponent c, Object o) {
        try {
            var m = c.getAccessor();
            m.setAccessible(true); // DTO records nested in package-private *Models classes
            return m.invoke(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static UUID uuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
