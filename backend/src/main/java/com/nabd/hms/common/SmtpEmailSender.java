package com.nabd.hms.common;

import com.nabd.hms.shared.integration.ExternalCallGuard;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final ExternalCallGuard guard;

    SmtpEmailSender(JavaMailSender mailSender, String from, ExternalCallGuard guard) {
        this.mailSender = mailSender;
        this.from = from;
        this.guard = guard;
    }

    @Override
    public void send(String to, String subject, String body) {
        guard.assertNoTransaction("smtp"); // OPS-012: a slow SMTP server must not hold a DB connection
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
