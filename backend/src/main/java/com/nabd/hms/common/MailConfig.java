package com.nabd.hms.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Spring Boot's own MailSenderAutoConfiguration only creates a {@link JavaMailSender} bean when
 * spring.mail.host is set, so mailSenderProvider is looked up lazily here — reaching for it when
 * smtpHost is blank would fail the whole context, not just fall back to logging.
 */
@Configuration
class MailConfig {

    @Bean
    EmailSender emailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                             @Value("${spring.mail.host:}") String smtpHost,
                             @Value("${spring.mail.username:}") String smtpUsername) {
        if (smtpHost.isBlank()) {
            return new LoggingEmailSender();
        }
        return new SmtpEmailSender(mailSenderProvider.getObject(), smtpUsername);
    }
}
