package com.nabd.comms.inbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/** The inbox exists only where a field key (base64, 32 bytes, from the secret store) is configured: no key, no plaintext fallback. */
@Configuration
@ConditionalOnProperty("comms.field-key")
class InboxFieldKeyConfig {
    @Bean
    FieldCrypto fieldCrypto(@Value("${comms.field-key}") String b64) {
        return new FieldCrypto(Base64.getDecoder().decode(b64));
    }
}
