package com.nabd.comms.schedule;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** COM-023.2: the leader lock (shedlock table from V1), same provider settings as Core's JobsConfig. */
@Configuration
@ConditionalOnProperty("comms.core.base-url")
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
class SchedulerLockConfig {

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbc).usingDbTime().build());
    }
}
