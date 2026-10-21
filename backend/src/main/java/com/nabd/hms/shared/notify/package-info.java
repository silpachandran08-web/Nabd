/** COM-004: the one notification contract (request type, purpose registry, port). Every module asks for a message through
 * {@link com.nabd.hms.shared.notify.NotificationPort}; the transport behind it (in-process WhatsApp today, the Comms Gateway
 * later) never shows. */
@org.springframework.modulith.NamedInterface("notify")
package com.nabd.hms.shared.notify;
