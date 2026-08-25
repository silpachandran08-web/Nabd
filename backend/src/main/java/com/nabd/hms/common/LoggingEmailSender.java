package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ponytail: logs instead of sending — used whenever SMTP_HOST isn't set (every local/test run today). */
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MOCK EMAIL] would send \"{}\" to {}", subject, to);
    }
}
