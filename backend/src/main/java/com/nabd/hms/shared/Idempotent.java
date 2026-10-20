package com.nabd.hms.shared;

import java.lang.annotation.*;

/** PLT-026: the endpoint needs an Idempotency-Key header; shared.idempotency.IdempotencyFilter makes retries run once.
 * Lives in the shared module's base package so it is exposed API (Spring Modulith): annotating a controller in
 * another module is not a dependency on shared internals. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
}
