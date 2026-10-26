package com.nabd.hms.organization.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PLT-059: on a controller method (or class) that WRITES module data. A request whose clinic lacks the module gets 403
 * MODULE_NOT_IN_PLAN, a killed feature 503 FEATURE_DISABLED. GET/HEAD/OPTIONS are never gated: history stays readable
 * after a downgrade or trial end (DENT-06).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresEntitlement {
    String value();
}
