package com.nabd.hms.shared.phiread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PLT-039: this GET reads patient data; after a 2xx response {@link PhiReadCapture} records one phi_read_log row per
 * patient it returned. The patient comes from the {patientId} path variable, else from the response body (the
 * {@link #key} UUID component of the body record, or of each element of the body list / the body's first list
 * component), else from the {queueEntryId} path variable's queue entry.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PhiRead {

    /** resource_type, e.g. "note". List endpoints end in "_list"; those rows do not count toward the volume rule. */
    String value();

    /** Body component that holds the patient id. */
    String key() default "patientId";

    /** purpose_code; a break-glass session always records break_glass instead. */
    String purpose() default "treatment";
}
