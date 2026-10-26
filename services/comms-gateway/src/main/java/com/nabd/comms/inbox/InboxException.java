package com.nabd.comms.inbox;

import org.springframework.http.HttpStatus;

public class InboxException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public InboxException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
