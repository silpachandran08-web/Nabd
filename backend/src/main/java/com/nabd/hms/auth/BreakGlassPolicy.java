package com.nabd.hms.auth;

import java.util.List;

/** PLT-012: what an emergency token can do. Fixed on purpose; v2 (per-patient Cedar context, ADR-08) replaces it. */
final class BreakGlassPolicy {
    private BreakGlassPolicy() {
    }

    /** Added to the caller's own grants. No create/edit/delete/approve/staff: break-glass is never Owner rights. */
    static final List<String> BREAK_GLASS_GRANTS = List.of("clinical:view", "patients:view");
    static final int MINUTES = 30;
    static final String ACTIVATED_EVENT = "iam.break_glass.activated.v1";
    static final String OWNER_WHATSAPP_EVENT = "iam.break_glass.owner_whatsapp.v1";
    static final String WHATSAPP_TEMPLATE = "emergency_access_used";
}
