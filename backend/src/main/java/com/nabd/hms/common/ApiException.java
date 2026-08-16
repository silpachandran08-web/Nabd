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

    public ApiException(HttpStatus status, String typeSlug, String title, String detail) {
        super(detail);
        this.status = status;
        this.typeSlug = typeSlug;
        this.title = title;
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
