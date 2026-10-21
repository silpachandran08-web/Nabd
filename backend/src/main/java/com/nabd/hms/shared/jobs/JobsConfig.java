package com.nabd.hms.shared.jobs;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * OPS-013: every cluster-wide @Scheduled job carries @SchedulerLock(name = ...), so with N replicas exactly one runs it
 * (lock row in shedlock, V1540) and the others skip (SkipLoggingLockProvider logs it). Inside the lock, JobRegistry
 * records job_last_success_timestamp / job_duration_seconds / job_failures_total{job=<lock name>}.
 * Per-replica jobs (in-memory cache or gauge refreshes) must NOT take the lock: every pod has to run them.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", order = Ordered.LOWEST_PRECEDENCE - 1) // outside JobRegistry's advisor
class JobsConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource, Clock clock) {
        return new SkipLoggingLockProvider(new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // one clock (the DB's) for every replica
                .build()), clock);
    }

    /** Runs inside ShedLock's advisor (higher order = inner), so a skipped run records nothing. */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor jobMetricsAdvisor(ObjectProvider<JobRegistry> registry) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(SchedulerLock.class),
                (MethodInterceptor) inv -> registry.getObject().record(inv));
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }
}
