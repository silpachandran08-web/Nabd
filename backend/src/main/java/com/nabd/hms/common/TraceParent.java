package com.nabd.hms.common;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/** OPS-015: the W3C traceparent to stamp on an outbox event, so a trace spans HTTP -> outbox -> consumer. */
final class TraceParent {

    private static final Pattern W3C = Pattern.compile("^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private TraceParent() {
    }

    /** The current span when the OpenTelemetry agent runs, else the request's own valid header, else null. */
    static String current() {
        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            return "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex();
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String header = attrs.getRequest().getHeader("traceparent");
            if (header != null && W3C.matcher(header).matches()) { // never store caller free text
                return header;
            }
        }
        return null;
    }
}
