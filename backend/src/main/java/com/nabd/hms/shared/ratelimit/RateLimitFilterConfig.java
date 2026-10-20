package com.nabd.hms.shared.ratelimit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The filter runs inside the Spring Security chain only (SecurityConfig); stop Boot also adding it to the servlet chain. */
@Configuration
class RateLimitFilterConfig {
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> r = new FilterRegistrationBean<>(filter);
        r.setEnabled(false);
        return r;
    }
}
