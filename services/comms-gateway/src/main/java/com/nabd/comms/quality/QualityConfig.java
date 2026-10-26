package com.nabd.comms.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration
class QualityConfig {

    @Bean
    QualityEvents qualityEvents(StringRedisTemplate valkey, ObjectMapper json, Clock clock, @Value("${comms.region:in}") String region) {
        return new QualityEvents(valkey, json, clock, region);
    }

    @Bean
    SenderQualityService senderQualityService(JdbcTemplate jdbc, TransactionTemplate tx, QualityEvents events, Clock clock) {
        return new SenderQualityService(jdbc, tx, events, clock);
    }

    @Bean
    ThrottleGate throttleGate(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        return new ThrottleGate(jdbc, tx, clock);
    }
}
