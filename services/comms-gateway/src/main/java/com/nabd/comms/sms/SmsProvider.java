package com.nabd.comms.sms;

import com.nabd.comms.provider.Provider;

import java.util.List;
import java.util.UUID;

/**
 * COM-026.1: the port to the Indian SMS BSP (procurement picks MSG91, Gupshup or Kaleyra). Every request carries the DLT entity id,
 * header and template id of the registered template the text matched; outcomes reuse the WhatsApp {@link Provider.SendResult} mapping.
 */
public interface SmsProvider {

    record SmsRequest(UUID messageId, String to, String dltEntityId, String header, String dltTemplateId, String text) {}

    /** status is 'delivered' or 'failed'; reports the BSP sends for intermediate states are dropped by the parser. */
    record Dlr(String providerMessageId, String status, String errorCode) {}

    /** The {provider} path segment of the delivery-report webhook. */
    String name();

    Provider.SendResult send(SmsRequest request);

    /** @throws IllegalArgumentException when the body is not a delivery report (400, a retry cannot fix it) */
    List<Dlr> parseDlr(byte[] body);
}
