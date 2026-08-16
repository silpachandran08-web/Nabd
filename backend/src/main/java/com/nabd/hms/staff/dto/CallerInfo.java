package com.nabd.hms.staff.dto;

/** What another module needs to know about the calling staff member — scope for row-level filtering, verification for the NB-041 gate. */
public record CallerInfo(String scope, boolean emailVerified, boolean mobileVerified) {
}
