package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-020: the Valkey revocation cache is only correct if EVERY statement that revokes a tenant session tells it.
 * Each "UPDATE sessions SET revoked_at" in main code must RETURN the ids and hand them to RevokedSessions.revoked
 * (directly or through AuthRepository.revoked) in the same Java statement. No Docker needed.
 */
class Ops020RevocationWriteThroughTest {

    static final Pattern REVOKE = Pattern.compile("UPDATE sessions SET revoked_at", Pattern.CASE_INSENSITIVE);

    @Test
    void everySessionRevokeStatementFeedsTheRevocationCache() throws Exception {
        List<String> sites = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = REVOKE.matcher(src);
                while (m.find()) {
                    int start = src.lastIndexOf(';', m.start()) + 1;
                    int end = src.indexOf(';', m.end());
                    String stmt = src.substring(start, end);
                    String where = f.getFileName() + ":" + (src.substring(0, m.start()).split("\n").length);
                    sites.add(where);
                    boolean returnsIds = stmt.contains("RETURNING id");
                    boolean tellsCache = stmt.contains("revoked(") || src.substring(end, Math.min(src.length(), end + 200)).contains("revoked(");
                    if (!returnsIds || !tellsCache) {
                        bad.add(where);
                    }
                }
            }
        }
        assertThat(sites).as("revoke statements found (AuthRepository 5, BreakGlass 1, Staff 1, Role 1)").hasSizeGreaterThanOrEqualTo(8);
        assertThat(bad).as("revoke statements that bypass the Valkey revocation cache").isEmpty();
    }
}
