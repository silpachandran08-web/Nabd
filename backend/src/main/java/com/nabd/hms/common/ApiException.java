package com.nabd.hms.common;

import org.springframework.http.HttpStatus;

/**
 * One exception type for every RFC 7807 error the API returns — the
 * type/title/status vary per call site, not per Java class. See
 * api/openapi.yaml's Problem schema.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String typeSlug;
    private final String title;
    private final java.util.Map<String, Object> extras;

    public ApiException(HttpStatus status, String typeSlug, String title, String detail) {
        this(status, typeSlug, title, detail, java.util.Map.of());
    }

    /** extras become top-level RFC 7807 members (e.g. retryAfterSeconds). */
    public ApiException(HttpStatus status, String typeSlug, String title, String detail, java.util.Map<String, Object> extras) {
        super(detail);
        this.status = status;
        this.typeSlug = typeSlug;
        this.title = title;
        this.extras = extras;
    }

    /** PLT-034: the one 404 problem (type not-found) the services share, instead of a local notFound() per class. */
    public static ApiException notFound() {
        return notFound("The requested resource was not found.");
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", detail);
    }

    public java.util.Map<String, Object> extras() {
        return extras;
    }

    public HttpStatus status() {
        return status;
    }

    public String typeSlug() {
        return typeSlug;
    }

    public String title() {
        return title;
    }
}
