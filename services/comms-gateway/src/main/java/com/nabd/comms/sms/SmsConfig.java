package com.nabd.comms.sms;

import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.gate.SendGate;
import com.nabd.comms.inbox.FieldCrypto;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** SMS exists only where a BSP is configured (and the field key that encrypts phone numbers at rest): no BSP, no SMS channel. */
@Configuration
@ConditionalOnProperty({"comms.field-key", "comms.sms.bsp.url"})
class SmsConfig {

    @Bean
    SmsProvider smsProvider(@Value("${comms.sms.provider:bsp}") String name, @Value("${comms.sms.bsp.url}") String url,
                            @Value("${comms.sms.bsp.api-key:}") String apiKey, ObjectMapper json) {
        return new BspSmsProvider(name, url, apiKey, json);
    }

    @Bean
    SmsService smsService(JdbcTemplate jdbc, TransactionTemplate tx, FieldCrypto crypto, SmsProvider provider, SendGate gate, StreamEventPublisher events,
                          ObjectMapper json, MeterRegistry metrics, Clock clock, @Value("${comms.region:in}") String region) {
        return new SmsService(jdbc, tx, crypto, provider, gate, events, json, metrics, clock, region);
    }
}
