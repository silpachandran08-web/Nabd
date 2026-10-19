package com.nabd.hms.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** PLT-028: the one time source. Main code reads "now" only through this bean (ClockUsageArchTest); tests swap in MutableClock. */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
