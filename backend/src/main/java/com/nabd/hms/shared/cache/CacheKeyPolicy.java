package com.nabd.hms.shared.cache;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-04 / PLT-052: builds cache keys and refuses to cache what the classification forbids. Pure key policy, no cache
 * client: a caller asks for a key before every put and caches nothing when this throws.
 *
 * <ul>
 *   <li>The value's tier is the strictest tier of the columns it is built from; a column missing from
 *       db/classification.yaml counts as PHI (default-deny).</li>
 *   <li>PHI needs a tenant key AND an explicit allow ({@code allowPhi}); PII and financial values need a tenant key;
 *       only operational values may use a global (tenant-less) key.</li>
 *   <li>Key parts are opaque ids: ':' and whitespace are refused so a part cannot forge another key.</li>
 * </ul>
 */
public final class CacheKeyPolicy {

    private final Map<String, DataTier> tiers;

    public CacheKeyPolicy(Map<String, DataTier> tiers) {
        this.tiers = Map.copyOf(tiers);
    }

    /** Reads db/classification.yaml ({@code schemas.<schema>.<table>.<column>: tier}). */
    @SuppressWarnings("unchecked")
    public static CacheKeyPolicy fromYaml(InputStream in) {
        Map<String, Object> doc = new Yaml().load(in);
        Map<String, DataTier> out = new HashMap<>();
        Map<String, Map<String, Map<String, String>>> schemas =
                (Map<String, Map<String, Map<String, String>>>) doc.getOrDefault("schemas", Map.of());
        schemas.forEach((schema, tables) -> tables.forEach((table, cols) -> cols.forEach((col, tier) ->
                out.put(schema + "." + table + "." + col, DataTier.valueOf(tier.toUpperCase(Locale.ROOT))))));
        return new CacheKeyPolicy(out);
    }

    /** Tier of {@code schema.table.column} (or {@code table.column} for public); unknown columns are PHI. */
    public DataTier tierOf(String column) {
        String c = column.toLowerCase(Locale.ROOT);
        return tiers.getOrDefault(c.chars().filter(ch -> ch == '.').count() == 1 ? "public." + c : c, DataTier.PHI);
    }

    /** Strictest tier among the source columns (an empty source list is refused: the caller must say what it caches). */
    public DataTier tierOf(Collection<String> sourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            throw new CacheRefusedException("cache value has no declared source columns");
        }
        return sourceColumns.stream().map(this::tierOf).min(Enum::compareTo).orElseThrow();
    }

    /**
     * Key for a value built from {@code sourceColumns}: {@code nabd:<namespace>:t:<tenant>:<parts>} or, for operational
     * data only, {@code nabd:<namespace>:g:<parts>}.
     *
     * @throws CacheRefusedException when the value must not be cached under these conditions
     */
    public String key(String namespace, UUID tenantId, Collection<String> sourceColumns, boolean allowPhi, String... parts) {
        DataTier tier = tierOf(sourceColumns);
        if (tier == DataTier.PHI && (tenantId == null || !allowPhi)) {
            throw new CacheRefusedException("PHI cache value needs a tenant key and an explicit allow (ADR-04): " + sourceColumns);
        }
        if (tier != DataTier.OPERATIONAL && tenantId == null) {
            throw new CacheRefusedException(tier + " cache value needs a tenant key: " + sourceColumns);
        }
        StringBuilder k = new StringBuilder("nabd:").append(safe(namespace));
        k.append(tenantId == null ? ":g" : ":t:" + tenantId);
        for (String p : parts) {
            k.append(':').append(safe(p));
        }
        return k.toString();
    }

    private static String safe(String part) {
        if (part == null || part.isEmpty() || part.chars().anyMatch(ch -> ch == ':' || Character.isWhitespace(ch))) {
            throw new CacheRefusedException("cache key part must be a non-empty opaque id without ':' or whitespace");
        }
        return part;
    }

    /** Thrown instead of returning a key; the caller must not cache the value. */
    public static final class CacheRefusedException extends IllegalArgumentException {
        public CacheRefusedException(String message) {
            super(message);
        }
    }
}
