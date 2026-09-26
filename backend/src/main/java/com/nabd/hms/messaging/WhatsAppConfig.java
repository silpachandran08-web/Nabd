package com.nabd.hms.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class WhatsAppConfig {

    @Bean
    WhatsAppClient whatsAppClient(WhatsAppProperties props) {
        return props.accessToken() == null || props.accessToken().isBlank()
                ? new LoggingWhatsAppClient()
                : new MetaWhatsAppClient(props);
    }
}
