package com.nabd.hms.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nabd.hms.NabdHmsApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-033 stage 2, no Docker. Writes target/architecture-violations.json (Modulith + ArchUnit); never fails on a
 * violation: scripts/ci/arch.py merges it with the SQL scans and applies the report / new-code / all gate.
 */
class ArchitectureRulesTest {

    static final Path OUT = Path.of("target", "architecture-violations.json");

    @Test
    void writesViolationReport() throws Exception {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ArchitectureRules.ROOT);
        assertTrue(main.size() > 100, "main classes imported");
        List<Map<String, String>> v = new ArrayList<>(ArchitectureRules.evaluate(main));
        try {
            for (String m : ApplicationModules.of(NabdHmsApplication.class).detectViolations().getMessages()) {
                v.add(Map.of("tool", "modulith", "rule", "modulith-verify", "module", ArchitectureRules.moduleOf(m), "detail", ArchitectureRules.headline(m)));
            }
        } catch (RuntimeException e) {  // a bootstrap failure is itself a finding, reported rather than hidden
            v.add(Map.of("tool", "modulith", "rule", "modulith-bootstrap", "module", "app", "detail", String.valueOf(e.getMessage())));
        }
        Files.createDirectories(OUT.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(OUT.toFile(), v);
        assertTrue(Files.size(OUT) > 0);
    }

    @Test
    void rulesFlagFixtureViolationsAndPassCleanFixtures() {
        JavaClasses fx = new ClassFileImporter().importClasses(BadController.class, CleanController.class, OrderRepository.class, OrderService.class);
        List<String> hits = ArchitectureRules.evaluate(fx).stream().map(m -> m.get("rule") + " " + m.get("detail")).toList();
        assertTrue(hits.stream().anyMatch(h -> h.startsWith("controllers-no-repository-or-jdbc") && h.contains("BadController") && h.contains("JdbcTemplate")), hits.toString());
        assertTrue(hits.stream().anyMatch(h -> h.startsWith("controllers-no-repository-or-jdbc") && h.contains("OrderRepository")), hits.toString());
        assertTrue(hits.stream().anyMatch(h -> h.startsWith("transactional-only-in-services") && h.contains("BadController")), hits.toString());
        assertTrue(hits.stream().noneMatch(h -> h.contains("CleanController") || h.contains("OrderService")), hits.toString());
        assertEquals("Cycle detected: Slice a -> Slice b -> Slice a", ArchitectureRules.headline("Cycle detected: Slice a -> \n    Slice b -> \n    Slice a\n  1. Dependencies of Slice a\n    - x"));
        assertEquals("owner", ArchitectureRules.moduleOf("Module 'owner' depends on non-exposed type com.nabd.hms.shared.security.TokenType within module 'shared'!"));
        assertEquals("queue", ArchitectureRules.moduleOf("Method <com.nabd.hms.queue.QueueController.list()> calls ..."));
    }

    // fixtures: a controller reaching into the data layer and opening a transaction, and a clean one
    static class OrderRepository { void save() {} }
    static class OrderService { @Transactional void place() {} }
    static class BadController {
        JdbcTemplate jdbc; OrderRepository repo;
        @Transactional void create() { repo.save(); jdbc.update("x"); }
    }
    static class CleanController { OrderService svc; void create() { svc.place(); } }
}
