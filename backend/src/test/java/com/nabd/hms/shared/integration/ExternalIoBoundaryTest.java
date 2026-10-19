package com.nabd.hms.shared.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-012 architecture rule (ArchUnit is not a dependency here, so plain source scan + reflection):
 * (1) HTTP clients are only built in shared.integration; JavaMailSender only in the SMTP sender; (2) no bean with a
 * {@code @Transactional} method holds an external-call seam, so no transactional method can reach one directly.
 */
class ExternalIoBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/nabd/hms");

    private static List<String> filesMatching(String regex) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).lines().anyMatch(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && l.matches(".*(" + regex + ").*"))) {
                    hits.add(MAIN.relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        return hits;
    }

    @Test
    void httpClientsAreOnlyBuiltInTheIntegrationPackage() throws IOException {
        assertThat(filesMatching("RestClient\\.builder\\(|RestClient\\.create\\(|new RestTemplate|RestTemplateBuilder|WebClient\\."
                + "|HttpClient\\.new|HttpURLConnection|ClientHttpRequestFactory\\(|new Socket\\("))
                .containsExactly("shared/integration/ResilientHttpClient.java");
        assertThat(filesMatching("JavaMailSender")).containsExactlyInAnyOrder("common/MailConfig.java", "common/SmtpEmailSender.java");
    }

    /** The seams that leave the process. WabaProvisioningGateway is left out on purpose: its only implementation
     * logs (no network) and it is still called inside ProvisioningStepRunner's step transaction; a real gateway must
     * move out of the step first (same pattern as the invite emails). */
    private static final Set<String> EXTERNAL = Set.of(
            "com.nabd.hms.messaging.WhatsAppClient", "com.nabd.hms.common.EmailSender", "com.nabd.hms.common.WhatsAppOtpSender",
            "com.nabd.hms.shared.integration.ResilientHttpClient", "org.springframework.mail.javamail.JavaMailSender",
            "org.springframework.web.client.RestClient");

    @Test
    void noTransactionalBeanHoldsAnExternalCallSeam() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> violations = new ArrayList<>();
        int transactionalBeans = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            boolean tx = c.isAnnotationPresent(Transactional.class);
            for (Method m : c.getDeclaredMethods()) {
                tx |= m.isAnnotationPresent(Transactional.class);
            }
            if (!tx) {
                continue;
            }
            transactionalBeans++;
            for (Field f : c.getDeclaredFields()) {
                if (EXTERNAL.contains(f.getType().getName())) {
                    violations.add(c.getSimpleName() + "." + f.getName() + " : " + f.getType().getSimpleName());
                }
            }
        }
        assertThat(transactionalBeans).as("scanner found the transactional beans").isGreaterThan(20);
        assertThat(violations).isEmpty();
    }
}
