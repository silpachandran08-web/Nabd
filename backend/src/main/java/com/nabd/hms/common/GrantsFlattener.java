package com.nabd.hms.common;

import java.util.ArrayList;
import java.util.List;

/** ModuleGrant[] (as stored in roles.grants) -> ["module:action", ...] for the JWT permissions claim. */
public final class GrantsFlattener {

    private GrantsFlattener() {
    }

    public static List<String> flatten(List<ModuleGrant> grants) {
        List<String> out = new ArrayList<>();
        for (ModuleGrant g : grants) {
            if (g.view()) out.add(g.module() + ":view");
            if (g.create()) out.add(g.module() + ":create");
            if (g.edit()) out.add(g.module() + ":edit");
            if (g.delete()) out.add(g.module() + ":delete");
            if (g.approve()) out.add(g.module() + ":approve");
            if (g.refundDiscount()) out.add(g.module() + ":refundDiscount");
            if (g.export()) out.add(g.module() + ":export");
        }
        return out;
    }
}
