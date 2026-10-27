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
        @DefaultValue("edge-1") String consumerName,
        /** IDC-005: public origin of the notice page (the Core public API); the consent message links {origin}{path from Core}. */
        @DefaultValue("") String noticeBaseUrl,
        /** ACC-040: soft hold taken when the patient picks a time (the offer itself holds nothing). */
        @DefaultValue("120") int pickHoldSeconds,
        /** ACC-040: lower-case phrases that bring the local emergency number at once. The list is clinically signed content: empty until signed. */
        @DefaultValue java.util.List<String> emergencyPhrases,
        /** ACC-040: region code (this cell's) to the emergency number the bot sends (108 IN, 997 SA, 998 AE, 999 QA). */
        @DefaultValue java.util.Map<String, String> emergencyNumbers,
        /** ACC-040: region code to the languages the patient may choose once (IN en/hi/kn/te, Gulf ar/en). */
        @DefaultValue java.util.Map<String, java.util.List<String>> languages) {
}
