package com.nabd.hms.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class MailConfigTest {

    private final MailConfig config = new MailConfig();

    @Test
    void fallsBackToLoggingWhenSmtpHostIsBlank() {
        ObjectProvider<JavaMailSender> neverCalled = new StubProvider(() -> {
            throw new AssertionError("a blank host must never look up a JavaMailSender bean");
        });
        EmailSender sender = config.emailSender(neverCalled, "", "user@example.com");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void usesSmtpWhenHostIsConfigured() {
        JavaMailSender mailSender = new JavaMailSenderImpl();
        ObjectProvider<JavaMailSender> provider = new StubProvider(() -> mailSender);
        EmailSender sender = config.emailSender(provider, "smtp.gmail.com", "user@example.com");
        assertThat(sender).isInstanceOf(SmtpEmailSender.class);
    }

    /** Minimal ObjectProvider stub — only getObject() is exercised by MailConfig. */
    private record StubProvider(java.util.function.Supplier<JavaMailSender> supplier) implements ObjectProvider<JavaMailSender> {
        @Override
        public JavaMailSender getObject() {
            return supplier.get();
        }

        @Override
        public JavaMailSender getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JavaMailSender getIfAvailable() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JavaMailSender getIfUnique() {
            throw new UnsupportedOperationException();
        }
    }
}
