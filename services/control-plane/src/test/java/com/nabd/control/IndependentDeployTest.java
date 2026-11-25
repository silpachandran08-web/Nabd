package com.nabd.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Control Plane is its own deployable: separate module, image and workflow, no shared code, and Core's build never includes it. */
class IndependentDeployTest {

    private static final String CORE_PKG = "com.nabd." + "hms";
    private static final Path ROOT = Path.of("../..");

    @Test
    void noCoreCodeOrBuildDependencyInEitherDirection() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(f).contains(CORE_PKG), f + " references Core code");
            }
        }
        assertFalse(Files.readString(Path.of("pom.xml")).contains("hms-backend"));
        assertFalse(Files.readString(ROOT.resolve("backend/pom.xml")).contains("control-plane"), "Core does not build the Control Plane");
        assertFalse(Files.readString(ROOT.resolve("Dockerfile")).contains("control-plane"), "Core image does not contain the Control Plane");
    }

    @Test
    void ownImageAndOwnPathScopedWorkflow() throws IOException {
        assertFalse(Files.readString(Path.of("Dockerfile")).contains("backend/"), "the image does not copy Core");
        String wf = Files.readString(ROOT.resolve(".github/workflows/control-plane.yml"));
        assertTrue(wf.contains("services/control-plane/**"));
        try (Stream<Path> wfs = Files.list(ROOT.resolve(".github/workflows"))) {
            for (Path other : wfs.filter(p -> !p.getFileName().toString().equals("control-plane.yml")).toList()) {
                assertFalse(Files.readString(other).contains("services/control-plane"), other + " would couple Core CI/deploy to the Control Plane");
            }
        }
    }

    /** NFR "Core unaffected by a Control Plane outage", the phase 1 part: Core has no address of this service to call. */
    @Test
    void coreHasNoConfiguredWayToCallTheControlPlane() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT.resolve("backend/src/main/resources"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("application.*\\.(yml|yaml|properties)")).toList()) {
                assertFalse(Files.readString(f).toUpperCase().matches("(?s).*CONTROL[-_.]?PLANE[-_.]?(URL|HOST|TOKEN).*"), f + " configures a call to the Control Plane");
            }
        }
    }
}
