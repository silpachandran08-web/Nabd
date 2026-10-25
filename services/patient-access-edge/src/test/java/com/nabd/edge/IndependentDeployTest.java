package com.nabd.edge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A deploy of the Edge must not need, build or trigger a Core deploy: separate module, image, workflow, no shared code. */
class IndependentDeployTest {

    private static final String CORE_PKG = "com.nabd." + "hms";
    private static final Path ROOT = Path.of("../..");

    @Test
    void edgeHasNoCoreCodeOrBuildDependency() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(f).contains(CORE_PKG), f + " references Core code");
            }
        }
        assertFalse(Files.readString(Path.of("pom.xml")).contains("hms-backend"));
        assertFalse(Files.readString(ROOT.resolve("backend/pom.xml")).contains("patient-access-edge"), "Core does not build the Edge");
        assertFalse(Files.readString(ROOT.resolve("Dockerfile")).contains("patient-access-edge"), "Core image does not contain the Edge");
    }

    @Test
    void edgeHasItsOwnImageAndItsOwnPathScopedWorkflow() throws IOException {
        String docker = Files.readString(Path.of("Dockerfile"));
        assertFalse(docker.contains("backend/"), "Edge image does not copy Core");
        String wf = Files.readString(ROOT.resolve(".github/workflows/patient-access-edge.yml"));
        assertTrue(wf.contains("services/patient-access-edge/**"));
        assertFalse(wf.contains("backend/**") || wf.contains("db/**"), "Edge workflow is not triggered by Core paths");
        try (Stream<Path> wfs = Files.list(ROOT.resolve(".github/workflows"))) {
            for (Path other : wfs.filter(p -> !p.getFileName().toString().equals("patient-access-edge.yml")).toList()) {
                assertFalse(Files.readString(other).contains("patient-access-edge"), other + " would couple Core CI/deploy to the Edge");
            }
        }
    }
}
