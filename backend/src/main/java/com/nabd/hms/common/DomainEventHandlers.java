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
 * instead of retrying 'no handler' forever. OPS-016: kept on purpose for regions without Valkey (no stream exists, dev,
 * most test contexts). With Valkey the dispatcher never claims domain rows (OutboxRepository, V1680) and only the
 * Streams relay (shared.relay.OutboxRelay) finishes them, after a successful XADD, so these handlers never run there.
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
                    if (t == DomainEventType.ORGANIZATION_CONFIG_CHANGED || t == DomainEventType.CATALOGUE_CHANGED) {
                        continue; // OPS-020: TenantConfigEvictions consumes it (cache invalidation on every instance; OPS-021 also the catalogue event)
                    }
                    if (t == DomainEventType.NOTIFICATION_REQUESTED) {
                        continue; // COM-004: messaging.NotificationRequestedHandler consumes it
                    }
                    if (t == DomainEventType.RX_SIGNED) {
                        continue; // PHL-012: pharmacy.RxSignedWorklistConsumer creates the worklist entry
                    }
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
