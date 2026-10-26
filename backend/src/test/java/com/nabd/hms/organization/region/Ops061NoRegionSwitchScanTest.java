package com.nabd.hms.organization.region;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-061 scan: production code has no 'KSA' literal, no switch or equals on a region code (rules come from the regions
 * table through organization.api.RegionRules), and no read of the old tenants.region column.
 */
class Ops061NoRegionSwitchScanTest {

    private static final Pattern KSA = Pattern.compile("\\bKSA\\b");
    private static final Pattern CASE_ON_REGION = Pattern.compile("case\\s+\"(IN|AE|SA|QA|KSA)\"");
    private static final Pattern EQUALS_ON_REGION = Pattern.compile("\"(IN|AE|SA|QA|KSA)\"\\s*\\.equals\\(|\\.equals\\(\\s*\"(IN|AE|SA|QA|KSA)\"\\s*\\)");
    private static final Pattern OLD_COLUMN = Pattern.compile("SELECT[^\"]*\\bregion\\b[^\"]*FROM tenants|\\bt\\.region\\b(?![\\w(])|tenants\\.region\\b(?![\\w(])");

    static List<String> violations(String pattern, Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).replace("AS region", "");
                    boolean hit = switch (pattern) {
                        case "ksa" -> KSA.matcher(line).find();
                        case "switch" -> CASE_ON_REGION.matcher(line).find() || EQUALS_ON_REGION.matcher(line).find();
                        default -> OLD_COLUMN.matcher(line).find();
                    };
                    if (hit) out.add(f + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        return out;
    }

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void noKsaLiteralLeftInProductionCode() throws IOException {
        assertThat(violations("ksa", MAIN)).isEmpty();
    }

    @Test
    void noSwitchOrEqualsOnARegionCode() throws IOException {
        assertThat(violations("switch", MAIN)).isEmpty();
    }

    @Test
    void noCodePathReadsTheOldTenantsRegionColumn() throws IOException {
        assertThat(violations("old", MAIN)).isEmpty();
    }

    @Test
    void theScanItselfCatchesTheOldPatterns(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bad.java"), "class Bad { String f(String r) { return \"KSA\".equals(r) ? \"SAR\" : \"INR\"; }\n"
                + " String g(String r) { return switch (r) { case \"AE\" -> \"x\"; default -> \"y\"; }; }\n"
                + " String h() { return \"SELECT region FROM tenants WHERE id = ?\"; } }\n");
        assertThat(violations("ksa", dir)).hasSize(1);
        assertThat(violations("switch", dir)).hasSize(2);
        assertThat(violations("old", dir)).hasSize(1);
    }
}
