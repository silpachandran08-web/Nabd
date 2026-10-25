package com.nabd.hms.billing.tax;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** FIN-009: sets a charge head's tax class, SAC/HSN and rate; the rate must come from the rules in force (never free typing). */
@Service
public class TaxHeadService {

    public record Request(String taxCategory, String sacHsn, BigDecimal gstRate, Boolean taxInclusive) {
    }

    public record Response(UUID id, String taxCategory, String sacHsn, BigDecimal gstRate, boolean taxInclusive, List<BigDecimal> allowedRates,
                           Preview preview) {
    }

    /** What one unit of the head at its base price would be billed as (tax-exclusive). */
    public record Preview(long baseMinor, long taxableMinor, long taxMinor, List<TaxEngine.Component> taxes) {
    }

    public record Picker(String jurisdiction, List<Question> questions, List<Outcome> outcomes) {
    }

    public record Question(String id, String text, List<Answer> answers) {
    }

    /** An answer leads to another question or to an outcome. */
    public record Answer(String label, String nextQuestion, String outcome) {
    }

    public record Outcome(String id, String taxCategory, BigDecimal gstRate, String note) {
    }

    private static final Pattern SAC_HSN = Pattern.compile("[0-9]{4,8}");

    private final TaxRepository repo;
    private final RegionRules regions;
    private final ClinicClock clock;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;

    TaxHeadService(TaxRepository repo, RegionRules regions, ClinicClock clock, TenantContext tenantContext,
                   AuditService audit, ActorDirectory actors, EventPublisher events) {
        this.repo = repo;
        this.regions = regions;
        this.clock = clock;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
    }

    @Transactional
    public Response update(UUID staffId, UUID id, String ip, Request req) {
        UUID tenantId = tenantContext.current();
        String jurisdiction = clock.region(tenantId);
        var cat = req.taxCategory();
        if (!List.of("exempt", "nil", "taxable").contains(cat)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "taxCategory must be exempt, nil or taxable.");
        }
        if (req.sacHsn() == null || !SAC_HSN.matcher(req.sacHsn()).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-sac-hsn", "Invalid SAC/HSN", "A SAC or HSN code is 4 to 8 digits.");
        }
        var allowed = repo.allowedRates(jurisdiction, clock.today(tenantId));
        BigDecimal rate;
        if ("taxable".equals(cat)) {
            rate = req.gstRate() == null ? null : req.gstRate().setScale(2, java.math.RoundingMode.HALF_UP);
            if (rate == null || rate.signum() == 0 || !allowed.contains(rate)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "rate-not-allowed", "Rate not allowed",
                        "Choose one of the allowed rates: " + allowed.stream().filter(r -> r.signum() > 0).map(BigDecimal::toPlainString).toList() + ".");
            }
        } else {
            if (req.gstRate() != null && req.gstRate().signum() != 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "rate-not-allowed", "Rate not allowed", "An exempt or nil head carries no rate.");
            }
            rate = BigDecimal.ZERO.setScale(2);
        }
        var before = repo.heads(tenantId, List.of(id)).get(id);
        if (before == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "charge-not-found", "Charge not found", "Charge not found");
        }
        boolean inclusive = Boolean.TRUE.equals(req.taxInclusive());
        repo.updateHeadTax(tenantId, id, cat, req.sacHsn(), rate, inclusive);
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "billing.charge_tax_set", "charge", id,
                Map.of("taxCategory", before.taxCategory() == null ? "" : before.taxCategory(), "rate", before.rate()),
                Map.of("taxCategory", cat, "rate", rate, "taxInclusive", inclusive));
        events.publish(tenantId, DomainEventType.CATALOGUE_CHANGED, id, Map.of("chargeHeadId", id, "status", "updated"));
        return new Response(id, cat, req.sacHsn(), rate, inclusive, allowed.stream().filter(r -> r.signum() > 0).toList(), preview(tenantId, id, cat, rate, inclusive, jurisdiction));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Preview preview(UUID tenantId, UUID id, String cat, BigDecimal rate, boolean inclusive, String jurisdiction) {
        BigDecimal base = repo.baseAmount(tenantId, id);
        var region = regions.region(jurisdiction);
        long minor = TaxService.minor(base, region.currency());
        var rules = "GST".equals(region.taxRegime()) ? new IndiaGst() : new FlatVat();
        var line = new TaxEngine.Line("p", minor, "taxable".equals(cat) ? TaxEngine.Category.TAXABLE : TaxEngine.Category.EXEMPT, rate, true, inclusive, false);
        var r = new RuleBasedTaxEngine(rules).compute(new TaxEngine.Cart(region.minorUnits(), false, false, 0, List.of(line))).lines().get(0);
        return new Preview(minor, r.taxableMinor(), r.taxMinor(), r.taxes());
    }

    /** Questions and outcomes per jurisdiction. Only India has a picker; the rates come from the rules in force. Rates and classes are a starting point: Needs external verification (CA, FIN-010). */
    @Transactional(readOnly = true)
    public Picker picker(String jurisdiction) {
        UUID tenantId = tenantContext.current();
        RegionRules.Region region;
        try {
            region = regions.region(jurisdiction);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Unknown jurisdiction.");
        }
        if (!"GST".equals(region.taxRegime())) return new Picker(jurisdiction, List.of(), List.of());
        var rates = repo.allowedRates(jurisdiction, clock.today(tenantId));
        BigDecimal cosmetic = new BigDecimal("18.00");
        String verify = " Needs external verification (CA, FIN-010).";
        return new Picker(jurisdiction, List.of(
                new Question("goods", "Is this a medicine or consumable that you sell?", List.of(
                        new Answer("Yes", null, "goods"), new Answer("No", "cosmetic", null))),
                new Question("cosmetic", "Is it a cosmetic or aesthetic procedure?", List.of(
                        new Answer("Yes", null, "cosmetic"), new Answer("No", "clinical", null))),
                new Question("clinical", "Is it a consultation, treatment or reconstructive service by the clinic?", List.of(
                        new Answer("Yes", null, "exempt"), new Answer("No", null, "other")))),
                List.of(
                        new Outcome("goods", "taxable", null, "Pick the rate for the item's HSN from: " + rates.stream().filter(r -> r.signum() > 0).map(BigDecimal::toPlainString).toList() + "." + verify),
                        new Outcome("cosmetic", "taxable", cosmetic, "Cosmetic and aesthetic procedures are taxable." + verify),
                        new Outcome("exempt", "exempt", BigDecimal.ZERO, "Health care services by a clinical establishment are exempt." + verify),
                        new Outcome("other", "nil", BigDecimal.ZERO, "Ask your CA before choosing a class for this head." + verify)));
    }
}
