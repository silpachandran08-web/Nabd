package com.nabd.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PLT-061 phase 1: the Control Plane as its own deployable with its own database. It holds no PHI and never connects to
 * Core's database while serving; it learns about tenants from Core's event stream (TenantFeed). The operator endpoints
 * still live in Core until the cut-over (docs/architecture/control-plane-extraction.md).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
