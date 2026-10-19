package com.nabd.hms.shared.idempotency;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The filter runs inside the Spring Security chain only (SecurityConfig); stop Boot also adding it to the servlet chain. */
@Configuration
class IdempotencyFilterConfig {
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> r = new FilterRegistrationBean<>(filter);
        r.setEnabled(false);
        return r;
    }
}
