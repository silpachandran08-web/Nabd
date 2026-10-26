package com.nabd.edge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tokens are service credentials injected by the environment; empty means "send no Authorization header". */
@ConfigurationProperties("edge")
public record EdgeProperties(
        @DefaultValue("in") String region,
        @DefaultValue("") String coreUrl,
        @DefaultValue("") String coreToken,
        @DefaultValue("") String commsUrl,
        @DefaultValue("") String commsToken,
        /** Base64, at least 32 bytes; shared with the Comms Gateway (COM-011 token scheme). Empty = every button tap is refused. */
        @DefaultValue("") String buttonKey,
        /** Acceptance criterion: 20 messages/min per phone (the NFR text says 30; the stricter acceptance value wins). */
        @DefaultValue("20") int phonePerMin,
        @DefaultValue("600") int ipPerMin,
        @DefaultValue("30") int projectionTtlSeconds,
        @DefaultValue("300") int holdSeconds,
        @DefaultValue("2000") int coreTimeoutMs,
        @DefaultValue("edge-1") String consumerName) {
}
