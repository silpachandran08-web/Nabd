package com.nabd.edge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** ACC-039.6: every threat row of the threat model names a control and an automated test that exists in this module. */
class ThreatModelTest {

    private static final Path DOC = Path.of("../../docs/architecture/patient-access-edge-threat-model.md");
    private static final Pattern ROW = Pattern.compile("^\\| (T\\d+) \\| (.+?) \\| (.+?) \\| `(\\w+)#(\\w+)` \\|$", Pattern.MULTILINE);

    @Test
    void everyThreatHasAControlAndAnExistingAutomatedTest() throws Exception {
        String doc = Files.readString(DOC);
        Matcher m = ROW.matcher(doc);
        int rows = 0;
        while (m.find()) {
            rows++;
            Path src = Path.of("src/test/java/com/nabd/edge/" + m.group(4) + ".java");
            assertTrue(Files.exists(src), m.group(1) + ": test class " + m.group(4) + " missing");
            assertTrue(Files.readString(src).contains("void " + m.group(5) + "("), m.group(1) + ": test method " + m.group(5) + " missing");
            assertTrue(m.group(3).length() > 20, m.group(1) + ": control text missing");
        }
        assertTrue(rows >= 8, "threat table has " + rows + " rows");
        for (String t : List.of("Bots", "Scraping", "OTP abuse", "Enumeration by phone", "Replayed button payloads")) {
            assertTrue(doc.contains(t), "T-077 threat missing: " + t);
        }
    }
}
