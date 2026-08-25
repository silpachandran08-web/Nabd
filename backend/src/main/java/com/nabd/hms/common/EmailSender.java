package com.nabd.hms.common;

/**
 * This interface is the seam: {@link MailConfig} picks {@link SmtpEmailSender} over
 * {@link LoggingEmailSender} once spring.mail.host (SMTP_HOST) is configured, without touching
 * any caller.
 */
public interface EmailSender {
    void send(String to, String subject, String body);
}
