package com.nabd.hms.shared.secrets;

import java.util.UUID;
import java.util.regex.Pattern;

/** OPS-058 path layout: installation secrets cell/&lt;cell&gt;/platform/&lt;name&gt;, tenant integration secrets
 * cell/&lt;cell&gt;/tenants/&lt;tenantId&gt;/&lt;name&gt;. A name is one flat segment: no '/', no '..'. */
public final class SecretPaths {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern CELL = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private SecretPaths() {
    }

    public static String platform(String cell, String name) {
        return "cell/" + cell(cell) + "/platform/" + name(name);
    }

    public static String tenant(String cell, UUID tenantId, String name) {
        return "cell/" + cell(cell) + "/tenants/" + tenantId + "/" + name(name);
    }

    private static String name(String n) {
        if (n == null || !NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("secret name must match " + NAME.pattern());
        }
        return n;
    }

    private static String cell(String c) {
        if (c == null || !CELL.matcher(c).matches()) {
            throw new IllegalStateException("nabd.secrets.cell is not configured");
        }
        return c;
    }
}
