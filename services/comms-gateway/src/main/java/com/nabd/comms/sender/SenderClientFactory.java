package com.nabd.comms.sender;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * COM-011: replaces the singleton MetaWhatsAppClient: one client per sender_accounts row, built on first use with that sender's
 * WABA credentials (token fetched from the secret store by access_token_ref) and cached by sender id. {@link #evict} on a
 * credential rotation. C is the provider client type (E9/COM-007 owns the Meta HTTP client; this class only chooses which one).
 */
public class SenderClientFactory<C> {

    public record Sender(UUID id, String phoneNumberId, String wabaId, String accessTokenRef, String provider) {}

    public interface Directory {
        /** Visible to the bound tenant: its own TENANT rows and the SHARED rows. Empty if unknown or not visible. */
        java.util.Optional<Sender> find(UUID senderAccountId);
    }

    public interface Secrets {
        String get(String ref);
    }

    private final Directory directory;
    private final Secrets secrets;
    private final Function<Credentials, C> builder;
    private final Map<UUID, C> cache = new ConcurrentHashMap<>();

    public record Credentials(Sender sender, String accessToken) {}

    public SenderClientFactory(Directory directory, Secrets secrets, Function<Credentials, C> builder) {
        this.directory = directory;
        this.secrets = secrets;
        this.builder = builder;
    }

    /** @throws IllegalArgumentException when the sender does not exist or is not visible to the bound tenant */
    public C forSender(UUID senderAccountId) {
        C hit = cache.get(senderAccountId);
        if (hit != null) {
            // visibility is re-checked on every use: a cached client must not serve a tenant that cannot see the sender
            directory.find(senderAccountId).orElseThrow(() -> unknown(senderAccountId));
            return hit;
        }
        Sender s = directory.find(senderAccountId).orElseThrow(() -> unknown(senderAccountId));
        return cache.computeIfAbsent(senderAccountId, id -> builder.apply(new Credentials(s, secrets.get(s.accessTokenRef()))));
    }

    public void evict(UUID senderAccountId) {
        cache.remove(senderAccountId);
    }

    private static IllegalArgumentException unknown(UUID id) {
        return new IllegalArgumentException("unknown sender account " + id);
    }
}
