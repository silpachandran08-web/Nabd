package com.nabd.hms.pharmacy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PHL-004: the minimum pharmacy mode an endpoint needs. Below it: External answers 403 pharmacy-mode-external, Hybrid
 * (needing In-house) 409 feature-requires-inhouse. Every /v1/pharmacy handler declares one (PharmacyModeGuardTest).
 * EXTERNAL means "any mode" (settings and capabilities, which the owner needs to leave External).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPharmacyMode {
    PharmacyMode value();
}
