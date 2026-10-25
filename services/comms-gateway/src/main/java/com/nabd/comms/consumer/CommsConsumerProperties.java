package com.nabd.comms.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("comms")
public record CommsConsumerProperties(String region, Consumer consumer) {
    public record Consumer(boolean enabled, int batchSize, int maxAttempts, long claimIdleMs) {
    }

    public String stream() {
        return "ev:" + region + ":comms";
    }

    public String dlq() {
        return "ev:" + region + ":dlq";
    }
}
