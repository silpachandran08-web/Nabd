package com.nabd.hms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NabdHmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NabdHmsApplication.class, args);
    }
}
