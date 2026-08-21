package com.nabd.hms.platform.billing;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.platform.billing.dto.DiscountPage;
import com.nabd.hms.platform.billing.dto.DiscountRequestCreate;
import com.nabd.hms.platform.billing.dto.DiscountResponse;
import com.nabd.hms.platform.billing.dto.PageMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.billing.BillingModels.DiscountRow;
import static com.nabd.hms.platform.billing.BillingModels.SubscriptionRow;

/**
 * NB-268: within-cap discounts auto-approve and apply immediately; above-cap requests queue for a
 * second operator to approve or reject. One fixed cap for now — split by role/tenant tier if that's
 * ever needed, nothing here assumes a single global number.
 */
@Service
public class DiscountService {

    private static final Logger log = LoggerFactory.getLogger(DiscountService.class);
    private static final BigDecimal AUTO_APPROVE_CAP_PERCENT = new BigDecimal("15");

    private final BillingRepository repo;

    DiscountService(BillingRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public DiscountResponse request(UUID operatorId, DiscountRequestCreate req) {
        SubscriptionRow subscription = repo.findByTenant(req.tenantId()).orElseThrow(this::noSubscription);

        boolean autoApprove = req.percent().compareTo(AUTO_APPROVE_CAP_PERCENT) <= 0;
        Instant now = Instant.now();
        UUID id = repo.insertDiscount(req.tenantId(), operatorId, req.percent(), req.reason(),
                autoApprove ? "auto_approved" : "pending", null, autoApprove ? now : null);

        if (autoApprove) {
            applyDiscount(subscription, req.percent());
            log.info("discount {} auto-approved: tenant {} {}% (within {}% cap)", id, req.tenantId(), req.percent(), AUTO_APPROVE_CAP_PERCENT);
        } else {
            log.info("discount {} queued for approval: tenant {} {}% (above {}% cap)", id, req.tenantId(), req.percent(), AUTO_APPROVE_CAP_PERCENT);
        }
        return toResponse(repo.findDiscountById(id).orElseThrow());
    }

    @Transactional
    public DiscountResponse approve(UUID id, UUID operatorId) {
        DiscountRow row = pendingOrThrow(id);
        requireNotSelfReview(row, operatorId);
        SubscriptionRow subscription = repo.findByTenant(row.tenantId()).orElseThrow(this::noSubscription);
        applyDiscount(subscription, row.percent());
        repo.updateDiscountStatus(id, "approved", operatorId, Instant.now());
        log.info("discount {} approved by {}: tenant {} {}%", id, operatorId, row.tenantId(), row.percent());
        return toResponse(repo.findDiscountById(id).orElseThrow());
    }

    @Transactional
    public DiscountResponse reject(UUID id, UUID operatorId) {
        DiscountRow row = pendingOrThrow(id);
        requireNotSelfReview(row, operatorId);
        repo.updateDiscountStatus(id, "rejected", operatorId, Instant.now());
        return toResponse(repo.findDiscountById(id).orElseThrow());
    }

    public DiscountPage list(int limit, String cursor) {
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<DiscountRow> rows = repo.listDiscountPage(limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<DiscountRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new DiscountPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    private void applyDiscount(SubscriptionRow subscription, BigDecimal percent) {
        BigDecimal factor = BigDecimal.ONE.subtract(percent.divide(BigDecimal.valueOf(100)));
        int newMrr = BigDecimal.valueOf(subscription.mrrCents()).multiply(factor)
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
        repo.updateMrr(subscription.tenantId(), newMrr);
    }

    // The one piece of maker-checker this queue needs without pulling in NB-056's general workflow
    // engine: whoever requested a discount can't be the one who waves it through.
    private void requireNotSelfReview(DiscountRow row, UUID operatorId) {
        if (row.requestedBy().equals(operatorId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "self-review-not-allowed", "Self-review not allowed",
                    "The operator who requested a discount can't approve or reject it.");
        }
    }

    private DiscountRow pendingOrThrow(UUID id) {
        DiscountRow row = repo.findDiscountById(id).orElseThrow(this::notFound);
        if (!"pending".equals(row.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "discount-not-pending", "Discount not pending",
                    "This discount request has already been " + row.status() + ".");
        }
        return row;
    }

    private DiscountResponse toResponse(DiscountRow d) {
        return new DiscountResponse(d.id(), d.tenantId(), d.tenantName(), d.requestedBy(), d.requestedByName(),
                d.percent(), d.reason(), d.status(), d.reviewedBy(), d.reviewedByName(), d.reviewedAt(), d.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested discount request was not found.");
    }

    private ApiException noSubscription() {
        return new ApiException(HttpStatus.BAD_REQUEST, "no-subscription", "No subscription",
                "This clinic has no active subscription to discount.");
    }
}
