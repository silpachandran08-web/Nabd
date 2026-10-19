package com.nabd.hms.staff.dto;

import com.nabd.hms.common.ModuleGrant;

import java.util.List;
import java.util.UUID;

/** presetCode/presetVersion (PLT-016): null for a custom role. */
public record RoleResponse(UUID id, String name, boolean builtIn, List<ModuleGrant> grants, boolean mfaRequired,
                           String presetCode, Integer presetVersion) {
}
