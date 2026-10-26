package com.nabd.comms.sender;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** COM-011: phone_routes access. Outbound sends record a route; the router reads the candidates of one phone on one sender. */
public interface RouteStore {

    record Route(UUID tenantId, Instant lastOutboundAt, Instant windowExpiresAt) {}

    /** Called after an outbound message is accepted: the 24 h customer-service window opens for this clinic. */
    void recordOutbound(UUID tenantId, UUID senderAccountId, byte[] phoneHash, Instant at);

    /** All clinics that messaged this phone on this sender, newest outbound first. */
    List<Route> candidates(UUID senderAccountId, byte[] phoneHash);
}
