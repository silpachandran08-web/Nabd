package com.nabd.hms.billing.tax;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.shared.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-008/FIN-009: resolves each line's tax class and rate (head setting, then the effective-dated rule for its SAC/HSN), decides
 * the document type and place of supply, runs the engine and stores the breakdown. Callers pass minor-unit amounts only.
 */
@Service
public class TaxService {

    private static final Map<String, JurisdictionRules> ADAPTERS = Map.of("GST", new IndiaGst(), "VAT", new FlatVat(), "NONE", new FlatVat());
    public static final String INVOICE = "invoice";
    public static final String BILL_OF_SUPPLY = "bill_of_supply";

    /** A line to tax. For a line already on the invoice pass {@code existing}=true: its stored class and rate are used, never re-resolved. */
    public record InLine(String key, long grossMinor, UUID catalogueId, BigDecimal rate, boolean existing, String storedCategory,
                         boolean storedInclusive, boolean storedInterState, String storedSacHsn) {
    }

    public record LineMeta(BigDecimal rate, String category, UUID ruleId, boolean inclusive, boolean interState, String sacHsn) {
    }

    public record Computation(TaxEngine.Result result, List<LineMeta> meta, String documentType, String buyerGstin, String currency) {
    }

    private final TaxRepository repo;
    private final RegionRules regions;
    private final ClinicClock clock;
    private final TenantContext tenantContext;
    private final com.nabd.hms.pharmacy.api.PharmacyTaxInputs pharmacy;

    TaxService(TaxRepository repo, RegionRules regions, ClinicClock clock, TenantContext tenantContext,
               com.nabd.hms.pharmacy.api.PharmacyTaxInputs pharmacy) {
        this.pharmacy = pharmacy;
        this.tenantContext = tenantContext;
        this.repo = repo;
        this.regions = regions;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Computation compute(UUID invoiceId, List<InLine> lines, long discountMinor, String requestBuyerGstin) {
        UUID tenantId = tenantContext.current();
        RegionRules.Region region = regions.region(clock.region(tenantId));
        JurisdictionRules rules = ADAPTERS.getOrDefault(region.taxRegime(), new FlatVat());
        LocalDate today = clock.today(tenantId);
        String[] meta = invoiceId == null ? null : repo.invoiceMeta(tenantId, invoiceId).orElse(null);
        String buyer = meta != null && meta[1] != null ? meta[1] : blankToNull(requestBuyerGstin);
        if (buyer != null && !PlaceOfSupply.validGstin(buyer)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-gstin", "Invalid GSTIN", "The buyer GSTIN is not a valid 15-character GSTIN.");
        }
        var clinic = repo.clinic();
        String docType = meta != null ? meta[0]
                : rules.billOfSupplyWhenUnregistered() && clinic.isPresent() && (!clinic.get().registered() || blankToNull(clinic.get().gstin()) == null)
                ? BILL_OF_SUPPLY : INVOICE;
        String clinicGstin = clinic.map(TaxRepository.Clinic::gstin).orElse(null);

        Map<UUID, TaxRepository.Head> heads = repo.heads(tenantId, lines.stream().filter(l -> !l.existing() && l.catalogueId() != null)
                .map(InLine::catalogueId).distinct().toList());
        var pharmacyInputs = pharmacy.inputs(heads.keySet()); // PHL-021: zero-rated / tax-inclusive price of stocked items
        List<TaxEngine.Line> engineLines = new ArrayList<>();
        List<LineMeta> out = new ArrayList<>();
        for (InLine l : lines) {
            LineMeta m = l.existing() ? stored(l)
                    : PharmacyTaxLines.apply(resolve(l, heads.get(l.catalogueId()), region.code(), today, clinicGstin, buyer), pharmacyInputs.get(l.catalogueId()));
            if (BILL_OF_SUPPLY.equals(docType) && !l.existing()) { // no tax is charged, so the line must not show a rate
                m = new LineMeta(BigDecimal.ZERO, m.category(), null, false, false, m.sacHsn());
            }
            out.add(m);
            engineLines.add(new TaxEngine.Line(l.key(), l.grossMinor(), TaxEngine.Category.valueOf(m.category()), m.rate(), true, m.inclusive(), m.interState()));
        }
        String currency = region.currency();
        var cart = new TaxEngine.Cart(region.minorUnits(), "INR".equals(currency), BILL_OF_SUPPLY.equals(docType), discountMinor, engineLines);
        // shortcut: whole-rupee round-off is by currency (INR) for every tenant; make it a per-tenant policy when a clinic asks for exact paise
        return new Computation(new RuleBasedTaxEngine(rules).compute(cart), out, docType, buyer, currency);
    }

    private static LineMeta stored(InLine l) {
        String cat = l.storedCategory() != null ? l.storedCategory() : l.rate().signum() > 0 ? "TAXABLE" : "NIL";
        return new LineMeta(l.rate(), cat, null, l.storedInclusive(), l.storedInterState(), l.storedSacHsn());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    LineMeta resolve(InLine l, TaxRepository.Head head, String jurisdiction, LocalDate today, String clinicGstin, String buyer) {
        if (head == null || head.taxCategory() == null) { // free text or a head the owner has not classified yet: the catalogue rate stands
            boolean goods = head != null && head.goods();
            return new LineMeta(l.rate(), l.rate().signum() > 0 ? "TAXABLE" : "NIL", null, head != null && head.inclusive(),
                    PlaceOfSupply.interState(clinicGstin, buyer, goods), head == null ? null : head.sacHsn());
        }
        boolean inter = PlaceOfSupply.interState(clinicGstin, buyer, head.goods());
        return switch (head.taxCategory()) {
            case "exempt" -> new LineMeta(BigDecimal.ZERO, "EXEMPT", null, false, false, head.sacHsn());
            case "taxable" -> {
                var rule = repo.rule(jurisdiction, head.sacHsn(), today).filter(r -> "taxable".equals(r.taxCategory()));
                yield new LineMeta(rule.map(TaxRepository.Rule::rate).orElse(head.rate()), "TAXABLE", rule.map(TaxRepository.Rule::id).orElse(null),
                        head.inclusive(), inter, head.sacHsn());
            }
            default -> new LineMeta(BigDecimal.ZERO, "NIL", null, false, false, head.sacHsn()); // nil, zero_rated, out_of_scope
        };
    }

    /** Writes the computed breakdown onto the invoice's lines (all of them, in display order) and the invoice's document type. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(UUID invoiceId, Computation c) {
        UUID tenantId = tenantContext.current();
        List<UUID> ids = repo.lineIds(tenantId, invoiceId);
        if (ids.size() != c.meta().size()) throw new IllegalStateException("line count changed during tax computation");
        for (int i = 0; i < ids.size(); i++) {
            var lr = c.result().lines().get(i);
            var m = c.meta().get(i);
            repo.storeLine(tenantId, ids.get(i), lr.discountMinor(), lr.taxableMinor(), m.category(), m.ruleId(), m.rate(), m.inclusive(),
                    m.interState(), m.sacHsn(), lr.taxes());
        }
        repo.setInvoiceTaxMeta(tenantId, invoiceId, c.documentType(), c.buyerGstin());
    }

    /** Stored components per line id (reprints read these, never recompute). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<TaxEngine.Component>> storedTaxes(UUID invoiceId) {
        return repo.lineTaxes(tenantContext.current(), invoiceId);
    }

    /** {documentType, buyerGstin} of a stored invoice. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String[] documentMeta(UUID invoiceId) {
        return repo.invoiceMeta(tenantContext.current(), invoiceId).orElse(new String[]{INVOICE, null});
    }

    public static long minor(BigDecimal amount, String currency) {
        return Money.of(amount, currency).amountMinor();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
}
