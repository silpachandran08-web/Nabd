package com.nabd.hms.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** PLT-034: a controller parameter (UUID) bound to the signed-in staff token's subject (CurrentCallerResolver). */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentStaff {
}
