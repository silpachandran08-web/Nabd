package com.nabd.comms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** COM-007: the Comms Gateway. Own database, own Valkey consumer group; talks to Core only through /internal/v1. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CommsGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommsGatewayApplication.class, args);
    }
}
