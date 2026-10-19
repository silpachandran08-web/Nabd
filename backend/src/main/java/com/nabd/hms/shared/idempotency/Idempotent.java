package com.nabd.hms.shared.idempotency;

import java.lang.annotation.*;

/** PLT-026: the endpoint needs an Idempotency-Key header; IdempotencyFilter makes retries run once. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
}
