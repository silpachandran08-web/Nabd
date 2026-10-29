package com.nabd.comms.inbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem+JSON with a stable "code" (e.g. SESSION_WINDOW_CLOSED); no request values in the body. */
@RestControllerAdvice(assignableTypes = {InboxInternalController.class, InboxActionsController.class})
@ConditionalOnProperty("comms.field-key")
class InboxErrors {
    @ExceptionHandler(InboxException.class)
    ResponseEntity<ProblemDetail> handle(InboxException e) {
        ProblemDetail p = ProblemDetail.forStatus(e.status());
        p.setTitle(e.code());
        p.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).body(p);
    }
}
