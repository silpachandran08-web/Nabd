package com.nabd.hms.platform.trial;

/** 429 with a Retry-After header; ApiException cannot carry headers, so TrialController answers this one itself. */
class TrialRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    TrialRateLimitException(long retryAfterSeconds) {
        super("Too many sign-up attempts; retry after " + retryAfterSeconds + " s.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
