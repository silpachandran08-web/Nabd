package com.nabd.hms.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test as a verifying test for a hazard-log entry (e.g. "H-07"); the safety report generator (CLN-007) reads it. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Hazard {
    String[] value();
}
