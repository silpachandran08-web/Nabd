package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OPS-015: one log-only OutboxEventHandler bean per {@link DomainEventType}, so the dispatcher marks those rows done
 * instead of retrying 'no handler' forever. OPS-016's relay replaces this class (one handler per type, same map key).
 */
@Configuration
class DomainEventHandlers {

    private static final Logger log = LoggerFactory.getLogger(DomainEventHandlers.class);

    @Bean
    static BeanDefinitionRegistryPostProcessor domainEventLogHandlers() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                for (DomainEventType t : DomainEventType.values()) {
                    registry.registerBeanDefinition("domainEventLogHandler." + t.type(),
                            new RootBeanDefinition(LogOnly.class, () -> new LogOnly(t.type())));
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            }
        };
    }

    record LogOnly(String eventType) implements OutboxEventHandler {
        @Override
        public void handle(OutboxEvent event) {
            log.debug("{} {} delivered (tenant {})", event.eventType(), event.id(), event.tenantId()); // ids only
        }
    }
}
