package com.nabd.hms.billing;

import com.nabd.hms.billing.CheckoutModels.InvoiceRow;
import com.nabd.hms.billing.PaymentIntentRepository.Intent;
import com.nabd.hms.billing.dto.PaymentRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.patient.api.GuardianLookup;
import com.nabd.hms.shared.money.Money;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FIN-020: UPI payment link / dynamic QR for an invoice balance, reconciled by idempotent gateway webhooks. Payments are only ever
 * written through {@link CheckoutService#recordPayment} (the FIN-001 atomic path) with method 'upi'. No card data, no PHI in
 * gateway notes or in the WhatsApp body (clinic name, amount, link).
 */
@Service
public class PaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);
    static final Duration LINK_TTL = Duration.ofHours(24);
    static final Duration QR_TTL = Duration.ofMinutes(10);
    static final Duration RECONCILE_AFTER = Duration.ofMinutes(15);
    private static final Duration RECONCILE_WINDOW = Duration.ofDays(7);
    private static final int MAX_BODY = 1_048_576;

    private final List<PaymentGatewayPort> gateways;
    private final PaymentIntentRepository repo;
    private final CheckoutRepository checkoutRepo;
    private final CheckoutService checkout;
    private final TenantContext tenantContext;
    private final TenantScope scope;
    private final ClinicClock clock;
    private final RegionRules regions;
    private final GuardianLookup guardians;
    private final NotificationPort notifications;
    private final AuditService audit;

    PaymentIntentService(List<PaymentGatewayPort> gateways, PaymentIntentRepository repo, CheckoutRepository checkoutRepo,
                         CheckoutService checkout, TenantContext tenantContext, TenantScope scope, ClinicClock clock,
                         RegionRules regions, GuardianLookup guardians, NotificationPort notifications, AuditService audit) {
        this.gateways = gateways;
        this.repo = repo;
        this.checkoutRepo = checkoutRepo;
        this.checkout = checkout;
        this.tenantContext = tenantContext;
        this.scope = scope;
        this.clock = clock;
        this.regions = regions;
        this.guardians = guardians;
        this.notifications = notifications;
        this.audit = audit;
    }

    public boolean hasGateways() {
        return !gateways.isEmpty();
    }

    public record Created(UUID intentId, String url, String qrPayload, Instant expiresAt) {
    }

    public record Status(String status, Instant paidAt, String gatewayRef) {
    }

    @Transactional
    public Created create(UUID invoiceId, String kind, BigDecimal amount, String recipient, UUID caregiverId, UUID staffId) {
        UUID tenantId = tenantContext.current();
        PaymentGatewayPort gw = gateways.stream().filter(g -> g.connected(tenantId)).findFirst().orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "gateway-not-connected", "No payment gateway connected",
                        "Connect a payment gateway in settings, or record the payment by hand.", Map.of("code", "GATEWAY_NOT_CONNECTED")));
        InvoiceRow inv = checkoutRepo.lockInvoice(tenantId, invoiceId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        if (InvoiceStatus.CLOSED.contains(inv.status()) || "paid".equals(inv.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-not-payable", "Invoice can't take payments", "This invoice is " + inv.status() + ".");
        }
        BigDecimal balance = inv.total().subtract(inv.paid());
        BigDecimal pay = amount == null ? balance : amount;
        if (pay.signum() <= 0 || pay.compareTo(balance) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "amount-exceeds-balance", "Amount is more than the balance",
                    "The balance due is " + balance.toPlainString() + ".", Map.of("code", "AMOUNT_EXCEEDS_BALANCE", "balance", balance));
        }
        boolean caregiver = "caregiver".equals(recipient);
        UUID payer = inv.patientId();
        if (caregiver) {
            if (caregiverId == null || inv.patientId() == null || !guardians.isGuardianOf(caregiverId, inv.patientId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not-a-caregiver", "Not a linked caregiver",
                        "That person is not a registered caregiver of this patient.", Map.of("code", "NOT_A_CAREGIVER"));
            }
            payer = caregiverId;
        }
        String currency = regions.region(checkoutRepo.findTenantRegion(tenantId).orElse("IN")).currency();
        long minor = Money.of(pay, currency).amountMinor();
        UUID id = UUID.randomUUID();
        Instant expires = clock.now().plus("qr".equals(kind) ? QR_TTL : LINK_TTL);
        PaymentGatewayPort.Order order = gw.createOrder(tenantId, id, minor, currency, kind, expires);
        repo.insert(tenantId, id, invoiceId, kind, gw.provider(), order.providerOrderId(), minor, currency,
                caregiver ? "caregiver" : "patient", payer, order.url(), order.qrPayload(), expires, staffId);
        if ("link".equals(kind) && payer != null) {
            String clinic = repo.clinicName(tenantId);
            NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PAYMENT_LINK, "payment_intent", id, payer,
                    null, null, List.of(clinic, currency + " " + pay.toPlainString(), order.url()), null, null, expires, "payint-" + id));
            if ("blocked".equalsIgnoreCase(out.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "no-consented-channel", "No consented channel", "That person has not agreed to messages.",
                        Map.of("code", "NO_CONSENTED_CHANNEL"));
            }
        }
        audit.record(tenantId, "staff", staffId, "Staff", "Staff", null, "billing.payment_intent_created", "invoice", invoiceId,
                null, Map.of("intentId", id, "kind", kind, "amountMinor", minor, "payer", caregiver ? "caregiver" : "patient"));
        return new Created(id, order.url(), order.qrPayload(), expires);
    }

    @Transactional
    public Status status(UUID intentId) {
        Intent in = repo.find(intentId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        String s = in.status();
        if ("created".equals(s) && in.expiresAt().isBefore(clock.now())) {
            repo.setStatus(intentId, "expired"); // an expired link cannot be paid at the gateway; show it as such
            s = "expired";
        }
        return new Status(s, in.paidAt(), in.gatewayRef());
    }

    // ---- webhook (persist-then-ack) ---------------------------------------------------------------------------------------

    /** Verifies the HMAC, stores the event (deduplicated by provider event id), then applies it. 401 BAD_SIGNATURE when unsigned. */
    public void receive(String provider, String tenantKey, byte[] body, String signature) {
        UUID tenantId = parseTenant(tenantKey);
        PaymentGatewayPort gw = gateways.stream().filter(g -> g.provider().equals(provider)).findFirst().orElse(null);
        if (gw == null || tenantId == null || body.length > MAX_BODY) {
            throw badSignature();
        }
        PaymentGatewayPort.Event ev = scope.run(tenantId, () -> {
            Optional<byte[]> secret = gw.webhookSecret(tenantId);
            if (secret.isEmpty() || signature == null || !MessageDigest.isEqual(hmacHex(secret.get(), body).getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            return gw.parse(body);
        });
        if (ev == null) {
            throw badSignature();
        }
        UUID rowId = scope.run(tenantId, () -> {
            Optional<UUID> fresh = repo.insertEvent(tenantId, provider, ev.eventId(), ev.type(), ev.providerOrderId(), ev.gatewayRef(), ev.amountMinor(), sha256(body));
            if (fresh.isPresent()) {
                return fresh.get();
            }
            return repo.findEvent(provider, ev.eventId()).filter(e -> e.processedAt() == null).map(e -> e.id()).orElse(null); // retry an event that failed before
        });
        if (rowId != null) {
            try {
                scope.run(tenantId, () -> process(provider, rowId, ev));
            } catch (RuntimeException e) {
                log.warn("gateway webhook event {} not applied: {}", rowId, e.getClass().getSimpleName()); // log-phi-ok: exception class name
                scope.run(tenantId, () -> repo.markEventError(rowId, e.getClass().getSimpleName())); // the poller retries the intent
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void process(String provider, UUID rowId, PaymentGatewayPort.Event ev) {
        Optional<UUID> intentId = ev.providerOrderId() == null ? Optional.empty() : repo.idByOrder(provider, ev.providerOrderId());
        if (intentId.isPresent()) {
            switch (ev.type()) {
                case "payment.captured", "order.paid" -> {
                    Intent in = repo.find(intentId.get()).orElseThrow();
                    if (ev.amountMinor() != null && ev.amountMinor() != in.amountMinor()) {
                        repo.markEventError(rowId, "amount-mismatch"); // money moved but not the quoted amount: a person decides
                        return;
                    }
                    applyPaid(intentId.get(), ev.gatewayRef());
                }
                case "payment.failed" -> repo.setStatus(intentId.get(), "failed");
                default -> { }
            }
        }
        repo.markEventProcessed(rowId, clock.now());
    }

    /** Idempotent: a second call for a paid intent does nothing. Records the payment through the FIN-001 atomic path. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyPaid(UUID intentId, String gatewayRef) {
        UUID tenantId = tenantContext.current();
        Intent in = repo.lock(intentId).orElseThrow();
        if ("paid".equals(in.status())) {
            return false;
        }
        String key = "gw-" + intentId;
        BigDecimal amount = new Money(in.amountMinor(), Currency.getInstance(in.currency())).amount();
        checkout.recordPayment(tenantId, in.invoiceId(), in.createdBy(), new PaymentRequest("upi", amount, gatewayRef), key);
        UUID paymentId = repo.tagPayment(tenantId, key, in.provider(), gatewayRef, intentId, in.payerKind(), in.payerRef());
        repo.markPaid(intentId, clock.now(), gatewayRef, paymentId);
        audit.record(tenantId, "system", null, "Payment gateway", "System", null, "billing.payment_intent_paid", "invoice", in.invoiceId(),
                null, Map.of("intentId", intentId, "paymentId", paymentId, "amountMinor", in.amountMinor(), "payerKind", in.payerKind()));
        return true;
    }

    // ---- reconciliation of lost webhooks ----------------------------------------------------------------------------------

    /** Re-queries intents still open after 15 minutes. Returns how many were settled (paid or failed). */
    public int reconcile(UUID tenantId) {
        Instant now = clock.now();
        List<UUID> open = scope.run(tenantId, () -> repo.openOlderThan(now.minus(RECONCILE_AFTER), now.minus(RECONCILE_WINDOW), 100));
        int settled = 0;
        for (UUID id : open) {
            try {
                settled += scope.run(tenantId, () -> reconcileOne(tenantId, id)) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("payment intent {} reconcile failed: {}", id, e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
        }
        return settled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    boolean reconcileOne(UUID tenantId, UUID id) {
        Intent in = repo.lock(id).orElseThrow();
        if (!"created".equals(in.status())) {
            return false;
        }
        PaymentGatewayPort gw = gateways.stream().filter(g -> g.provider().equals(in.provider())).findFirst().orElse(null);
        if (gw == null) {
            return false;
        }
        PaymentGatewayPort.OrderState st = gw.fetchOrder(tenantId, in.providerOrderId());
        if (st.paid()) {
            return applyPaid(id, st.gatewayRef());
        }
        if (st.failed()) {
            repo.setStatus(id, "failed");
            return true;
        }
        if (in.expiresAt().isBefore(clock.now())) {
            repo.setStatus(id, "expired");
        }
        return false;
    }

    private static UUID parseTenant(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException badSignature() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "bad-signature", "Bad signature", "The webhook signature is missing or wrong.", Map.of("code", "BAD_SIGNATURE"));
    }

    private static String hmacHex(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
