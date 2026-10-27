package com.nabd.hms.billing;

import com.nabd.hms.billing.CheckoutModels.LineItemInput;
import com.nabd.hms.billing.CheckoutModels.PharmacyChargeRow;
import com.nabd.hms.billing.dto.CheckoutRequest.DiscountRequest;
import com.nabd.hms.billing.dto.LineItemRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.cache.ConfigCache;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-003: the one place a bill line gets its price. The client sends chargeCode + quantity; name, category, tier price
 * and tax come from charge_catalogue. Changing any price-bearing field (override, free-text line, discount) needs
 * billing.discount; an override or discount also needs a coded reason.
 */
@Component
class BillingPricing {

    // shortcut: billing-local reason list until the shared T-321 list lands; swap this set for that list then.
    static final Set<String> REASON_CODES = Set.of("goodwill", "staff", "price_match", "waiver", "pricing_error", "other");
    static final String LEGACY_REASON = "unspecified";

    record DiscountInput(String kind, BigDecimal value, BigDecimal amount, String reasonCode, String note) {
    }

    private final CheckoutRepository repo;
    private final com.nabd.hms.organization.api.RegionRules regions;
    private final ConfigCache configCache;
    private final com.nabd.hms.pharmacy.api.PharmacyTaxInputs pharmacy;

    BillingPricing(CheckoutRepository repo, com.nabd.hms.organization.api.RegionRules regions, ConfigCache configCache,
                   com.nabd.hms.pharmacy.api.PharmacyTaxInputs pharmacy) {
        this.pharmacy = pharmacy;
        this.repo = repo;
        this.regions = regions;
        this.configCache = configCache;
    }

    /** OPS-021: prices come from the versioned config cache (price and active flag only; stock is never cached). */
    private java.util.Map<String, PharmacyChargeRow> cachedCatalogue(UUID tenantId, List<String> codes,
                                                                     java.util.function.Function<List<String>, java.util.Map<String, PharmacyChargeRow>> db) {
        java.util.Map<String, PharmacyChargeRow> out = new java.util.HashMap<>();
        for (String code : codes) {
            PharmacyChargeRow row = configCache.get(tenantId, ConfigCache.Domain.CATALOGUE, code, PharmacyChargeRow.class,
                    () -> db.apply(List.of(code)).get(code));
            if (row != null) {
                out.put(code, row);
            }
        }
        return out;
    }

    /** trusted = a server-built bill (package sale): lines are not client input, so no catalogue/authority check. */
    List<LineItemInput> resolveLines(UUID tenantId, List<LineItemRequest> lines, boolean followUpEligible,
                                     boolean canDiscount, boolean trusted, UUID staffId) {
        List<LineItemInput> out = new ArrayList<>(lines.size());
        // OPS-061: a free-text line without its own rate takes the region's headline rate (AE 5, SA 15); none (India GST slabs, Qatar) is 0
        BigDecimal regionTax = java.util.Objects.requireNonNullElse(
                regions.region(repo.findTenantRegion(tenantId).orElse("IN")).standardTaxRate(), BigDecimal.ZERO);
        List<String> codes = lines.stream().map(LineItemRequest::chargeCode).distinct().toList();
        java.util.Map<String, PharmacyChargeRow> catalogue = trusted ? java.util.Map.of()
                : configCache.enabled() ? cachedCatalogue(tenantId, codes, c -> repo.findCatalogueCharges(tenantId, c))
                : repo.findCatalogueCharges(tenantId, codes);
        for (LineItemRequest li : lines) {
            PharmacyChargeRow c = catalogue.get(li.chargeCode());
            if (c != null && (c.stocked() || c.isRx()) && !c.active()) { // H-04: an inactive Rx code must not fall through to free text
                throw new ApiException(HttpStatus.CONFLICT, "item-inactive", "Item no longer sold",
                        c.name() + " has been deactivated and can't be billed. Remove it from the bill.");
            }
            if (c == null || !c.active()) {
                out.add(freeText(li, canDiscount, trusted, staffId, regionTax));
                continue;
            }
            boolean followUp = followUpEligible && "Consultation".equals(c.category()) && c.followUpAmount() != null;
            BigDecimal list = followUp ? c.followUpAmount() : c.baseAmount();
            if (c.doctorOverride() && canDiscount && li.unitPrice() != null) {
                list = li.unitPrice(); // the flag means "the doctor sets this price"
            }
            String tier = followUp ? "follow_up" : "normal";
            var o = li.override();
            if (o == null) {
                if (c.stocked()) pharmacy.requireWithinMrp(c.id(), list); // PHL-021
                out.add(new LineItemInput(li.chargeCode(), c.name(), c.category(), li.quantity(), list, c.taxRatePercent(),
                        c.id(), list, tier, null, null, null, c.isRx(), li.prescriptionItemId(), li.shortfallReason()));
                continue;
            }
            requireDiscountRight(canDiscount);
            String reason = requireReason(o.reasonCode());
            if (o.unitPrice() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "An override needs a unit price.");
            }
            if (c.stocked()) pharmacy.requireWithinMrp(c.id(), o.unitPrice()); // PHL-021
            out.add(new LineItemInput(li.chargeCode(), c.name(), c.category(), li.quantity(), o.unitPrice(), c.taxRatePercent(),
                    c.id(), list, tier, reason, o.note(), staffId, c.isRx(), li.prescriptionItemId(), li.shortfallReason()));
        }
        return out;
    }

    private LineItemInput freeText(LineItemRequest li, boolean canDiscount, boolean trusted, UUID staffId, BigDecimal regionTax) {
        if (!trusted && !canDiscount) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-charge", "Unknown charge",
                    "'" + li.chargeCode() + "' is not an active charge in the price list.");
        }
        if (li.chargeName() == null || li.chargeName().isBlank() || li.unitPrice() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "A charge that is not in the price list needs a name and a unit price.");
        }
        var o = li.override();
        String reason = trusted ? null : requireReason(o == null ? null : o.reasonCode());
        return new LineItemInput(li.chargeCode(), li.chargeName(), li.category() == null || li.category().isBlank() ? "Service" : li.category(),
                li.quantity(), li.unitPrice(), li.taxRatePercent() != null ? li.taxRatePercent() : regionTax, null,
                li.unitPrice(), "custom", reason, o == null ? null : o.note(), trusted ? null : staffId, false, li.prescriptionItemId(), null);
    }

    /** Ledger rows for this request. remaining = combined subtotal minus discounts already on the invoice. */
    List<DiscountInput> resolveDiscounts(BigDecimal legacyDiscount, List<DiscountRequest> discounts, boolean canDiscount, BigDecimal remaining) {
        List<DiscountInput> out = new ArrayList<>();
        if (legacyDiscount.signum() > 0) {
            requireDiscountRight(canDiscount);
            out.add(new DiscountInput("amount", legacyDiscount, legacyDiscount, LEGACY_REASON, null));
        }
        for (DiscountRequest d : discounts) {
            requireDiscountRight(canDiscount);
            String reason = requireReason(d.reasonCode());
            boolean pct = "percent".equals(d.kind());
            if (!pct && !"amount".equals(d.kind())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "A discount is an 'amount' or a 'percent'.");
            }
            BigDecimal base = remaining.subtract(out.stream().map(DiscountInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal amount = pct ? base.multiply(d.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) : d.value();
            out.add(new DiscountInput(d.kind(), d.value(), amount, reason, d.note()));
        }
        BigDecimal sum = out.stream().map(DiscountInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(remaining) > 0 || out.stream().anyMatch(x -> x.amount().signum() <= 0)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "discount-exceeds-subtotal", "Discount too large",
                    "The discount can't be more than the remaining subtotal.");
        }
        return out;
    }

    static void requireDiscountRight(boolean canDiscount) {
        if (!canDiscount) {
            throw new ApiException(HttpStatus.FORBIDDEN, "discount-not-permitted", "Discount not permitted",
                    "Your role cannot change a price or apply a discount at checkout.");
        }
    }

    private static String requireReason(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "reason-required", "Reason required",
                    "Pick a reason for this price change or discount.");
        }
        if (!REASON_CODES.contains(code)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-reason", "Unknown reason",
                    "'" + code + "' is not a valid reason code.");
        }
        return code;
    }
}
