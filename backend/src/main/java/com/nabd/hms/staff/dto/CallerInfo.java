package com.nabd.hms.staff.dto;

import java.util.List;

/** What another module needs to know about the calling staff member — scope for row-level
 * filtering, verification for the NB-041 gate, fieldGrants for NB-052's field-level restrictions
 * (an empty list means no custom restriction — the default, full-access case). */
public record CallerInfo(String scope, boolean emailVerified, boolean mobileVerified, List<String> fieldGrants) {
}
