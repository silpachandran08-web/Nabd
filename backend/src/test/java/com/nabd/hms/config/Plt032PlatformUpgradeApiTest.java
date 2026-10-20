package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-032: the app runs on a supported Boot line, Java 21, with Tomcat on virtual threads; build/runtime files agree. */
@org.springframework.test.context.TestPropertySource(properties = "spring.threads.virtual.enabled=true")
class Plt032PlatformUpgradeApiTest extends ApiTestBase {

    @Autowired
    WebServerApplicationContext web;

    @Test
    void runsOnBoot4AndJava21() {
        assertThat(SpringBootVersion.getVersion()).startsWith("4.");
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

    @Test
    void whenSwitchedOnTomcatRequestsRunOnVirtualThreads() {
        var tomcat = ((TomcatWebServer) web.getWebServer()).getTomcat();
        assertThat(tomcat.getConnector().getProtocolHandler().getExecutor()).isInstanceOf(VirtualThreadExecutor.class);
        // a real request still answers on that executor
        assertThat(http.getForEntity("/.well-known/jwks.json", String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void buildAndRuntimeFilesAllSayJava21() throws IOException {
        assertThat(Files.readString(Path.of("pom.xml"))).contains("<java.version>21</java.version>");
        String docker = Files.readString(Path.of("../Dockerfile"));
        assertThat(docker).contains("distroless/java21-debian12").contains("eclipse-temurin:21-jre").doesNotContain("java17").doesNotContain(":17-");
        try (Stream<Path> wf = Files.list(Path.of("../.github/workflows"))) {
            wf.filter(p -> p.toString().endsWith(".yml")).forEach(p -> {
                try {
                    String y = Files.readString(p);
                    assertThat(y).as(p.toString()).doesNotContainPattern("java-version: ['\"]?17").doesNotContain("JAVA_VERSION: \"17\"");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
