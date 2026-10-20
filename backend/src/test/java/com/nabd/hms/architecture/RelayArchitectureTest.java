package com.nabd.hms.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OPS-016: a hard rule (fails the build): the outbox relay never calls external APIs, so it has no HTTP client dependency. */
class RelayArchitectureTest {

    static final String RELAY = ArchitectureRules.ROOT + ".shared.relay..";

    static final ArchRule RELAY_HAS_NO_HTTP_CLIENT = noClasses().that().resideInAPackage(RELAY)
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..", "org.springframework.http.client..", "org.apache.hc..",
                    "org.apache.http..", "okhttp3..", "feign..", ArchitectureRules.ROOT + ".shared.integration..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URL")
            .because("OPS-016: the relay only reads Postgres and writes Valkey Streams; external calls belong to consumers");

    @Test
    void relayCodeHasNoHttpClientDependency() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ArchitectureRules.ROOT + ".shared.relay");
        assertTrue(main.contain(ArchitectureRules.ROOT + ".shared.relay.OutboxRelay"), "relay classes imported");
        RELAY_HAS_NO_HTTP_CLIENT.check(main);
    }

    @Test
    void ruleCatchesAnHttpCallInRelayCode() {
        JavaClasses fx = new ClassFileImporter().importClasses(com.nabd.hms.shared.relay.HttpCallingRelayFixture.class);
        assertTrue(RELAY_HAS_NO_HTTP_CLIENT.evaluate(fx).hasViolation());
    }
}
