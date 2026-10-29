package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.organization.setup.SetupModels.ChargeHeadRow;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-015 per-doctor consultation fees + FIN-016 price change log. The log is written here, in the same transaction as the
 * update (not by a trigger: the actor is known). Issued invoices keep their own line prices, so a change applies from the next bill.
 */
@Service
public class PricingService {

    @Schema(name = "DoctorFeeInput")
    public record FeeInput(UUID doctorId, BigDecimal amount) {
    }

    @Schema(name = "DoctorFeeRequest")
    public record FeesRequest(List<FeeInput> fees) {
    }

    @Schema(name = "DoctorFee")
    public record FeeView(UUID doctorId, String doctorName, BigDecimal amount) {
    }

    @Schema(name = "DoctorFeesResponse")
    public record FeesResponse(List<FeeView> fees) {
    }

    @Schema(name = "PriceChange")
    public record ChangeView(UUID id, UUID chargeId, String chargeName, String field, UUID doctorId, String oldValue, String newValue,
                             String actor, Instant at) {
    }

    @Schema(name = "PriceChangePage")
    public record ChangePage(List<ChangeView> items, String nextCursor) {
    }

    /** FIN-017: one head of a starter list, and of the bulk request (the owner's edited copy). */
    @Schema(name = "PriceTemplateHead")
    public record TemplateHead(String code, String name, String category, BigDecimal baseAmount, String taxCategory, BigDecimal taxRatePercent,
                               String sacHsn, Integer displayOrder) {
    }

    /** reviewed = false: the list's tax categories, rates and SAC/HSN codes still await the billing SME and CA (open decision 64). */
    @Schema(name = "PriceTemplate")
    public record TemplateView(UUID templateId, String specialty, String jurisdiction, int version, boolean reviewed, String reviewNote,
                               String currency, List<TemplateHead> heads) {
    }

    @Schema(name = "BulkChargesRequest")
    public record BulkRequest(UUID templateId, List<TemplateHead> heads) {
    }

    /** Codes created, and codes skipped because the price master already had them (never overwritten). */
    @Schema(name = "BulkChargesResponse")
    public record BulkResponse(List<String> created, List<String> skipped) {
    }

    static final Set<String> SPECIALTIES = Set.of("dental", "derma", "gp", "paediatric", "physio", "other");
    static final Set<String> TAX_CATEGORIES = Set.of("exempt", "nil", "zero_rated", "taxable");
    static final int MAX_HEADS = 50;
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final java.util.regex.Pattern SAC_HSN = java.util.regex.Pattern.compile("[0-9]{4,8}");
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("\\p{Cntrl}");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999");

    static final int MAX_FEES = 200;
    static final int CSV_ROWS = 5000;

    private final PricingRepository repo;
    private final SetupRepository setupRepo;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final SetupService setup;
    private final RegionRules regions;
    private final PriceChangeLog pricingLog;

    PricingService(PricingRepository repo, SetupRepository setupRepo, TenantContext tenantContext, ActorDirectory actors, AuditService audit,
                   EventPublisher events, ClinicClock clock, SetupService setup, RegionRules regions, PriceChangeLog pricingLog) {
        this.regions = regions;
        this.pricingLog = pricingLog;
        this.repo = repo;
        this.setupRepo = setupRepo;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.setup = setup;
    }

    @Transactional(readOnly = true)
    public FeesResponse fees(UUID chargeId) {
        UUID tenantId = tenantContext.current();
        consultationHead(tenantId, chargeId);
        return view(tenantId, chargeId);
    }

    /** PUT semantics: the list names the doctors to change; amount null clears that doctor's override (back to the base amount). */
    @Transactional
    public FeesResponse setFees(UUID chargeId, UUID actorId, String ip, FeesRequest req) {
        UUID tenantId = tenantContext.current();
        ChargeHeadRow head = consultationHead(tenantId, chargeId);
        if (req == null || req.fees() == null || req.fees().size() > MAX_FEES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Send between 0 and " + MAX_FEES + " doctor fees.");
        }
        Set<UUID> seen = new HashSet<>();
        for (FeeInput f : req.fees()) {
            if (f == null || f.doctorId() == null || !seen.add(f.doctorId())
                    || (f.amount() != null && (f.amount().signum() < 0 || f.amount().scale() > 2 || f.amount().compareTo(new BigDecimal("9999999999")) > 0))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                        "Each doctor appears once with an amount of 0 or more (two decimals), or null to clear.");
            }
            var doctor = actors.findActor(tenantId, f.doctorId());
            if (doctor.isEmpty() || !doctor.get().clinician()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-doctor", "Unknown doctor", "A fee can only be set for a doctor of this clinic.");
            }
        }
        Map<UUID, BigDecimal> before = repo.feeMap(tenantId, chargeId);
        for (FeeInput f : req.fees()) {
            BigDecimal old = before.get(f.doctorId());
            if (f.amount() == null) {
                if (old != null) repo.deleteFee(tenantId, chargeId, f.doctorId());
            } else {
                repo.upsertFee(tenantId, chargeId, f.doctorId(), f.amount(), actorId);
            }
            if (!same(old, f.amount())) {
                repo.log(tenantId, chargeId, f.doctorId(), "doctor_fee", str(old), str(f.amount()), actorId);
            }
        }
        events.publish(tenantId, DomainEventType.CATALOGUE_CHANGED, chargeId, Map.of("chargeHeadId", chargeId, "status", "updated")); // config cache invalidation
        record(tenantId, actorId, ip, "setup.doctor_fees_changed", chargeId, Map.of("count", before.size()), Map.of("count", repo.fees(tenantId, chargeId).size()));
        return view(tenantId, chargeId);
    }

    /** FIN-017: the starter list for a specialty in a market (default: the clinic's own). Amounts are suggestions to edit. */
    @Transactional(readOnly = true)
    public TemplateView template(String specialty, String jurisdiction) {
        UUID tenantId = tenantContext.current();
        String sp = specialty == null ? "" : specialty.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SPECIALTIES.contains(sp)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "specialty must be one of dental, derma, gp, paediatric, physio, other.");
        }
        String market = jurisdiction == null || jurisdiction.isBlank() ? market(tenantId) : jurisdiction.trim().toUpperCase(java.util.Locale.ROOT);
        RegionRules.Region region;
        try {
            region = regions.region(market);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Unknown jurisdiction.");
        }
        PricingRepository.Template t = repo.template(sp, market)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "template-not-found", "Starter list not found", "There is no starter price list for that specialty and market."));
        return new TemplateView(t.id(), t.specialty(), t.jurisdiction(), t.version(), t.reviewed(), t.reviewNote(), region.currency(), t.heads());
    }

    /**
     * FIN-017: creates the owner's edited starter list in one transaction. Add-only: a code the price master already has (any case) is
     * skipped and never overwritten. One change-log entry (base amount) per created head; the whole list is audited once.
     */
    @Transactional
    public BulkResponse applyTemplate(UUID actorId, String ip, BulkRequest req) {
        UUID tenantId = tenantContext.current();
        ActorDirectory.Actor owner = actors.findActor(tenantId, actorId).filter(a -> "Owner".equals(a.role()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "Owner only", "Only the clinic owner can apply a starter price list."));
        if (req == null || req.templateId() == null || req.heads() == null || req.heads().isEmpty() || req.heads().size() > MAX_HEADS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Send a templateId and between 1 and " + MAX_HEADS + " heads.");
        }
        PricingRepository.Template t = repo.template(req.templateId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "template-not-found", "Starter list not found", "Starter list not found."));
        String market = market(tenantId);
        if (!t.jurisdiction().equals(market)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "template-market-mismatch", "Wrong market",
                    "This starter list is for another market; pick the list for your clinic's country.");
        }
        Set<BigDecimal> rates = new HashSet<>();
        repo.templateTaxableRates(market).forEach(r -> rates.add(r.setScale(2, java.math.RoundingMode.HALF_UP)));
        BigDecimal standard = regions.region(market).standardTaxRate();
        if (standard != null && standard.signum() > 0) rates.add(standard.setScale(2, java.math.RoundingMode.HALF_UP));
        Set<String> seen = new HashSet<>();
        for (TemplateHead h : req.heads()) {
            validate(h, rates);
            if (!seen.add(h.code().trim().toUpperCase(java.util.Locale.ROOT))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Each code appears once in the list.");
            }
        }
        Set<String> existing = new HashSet<>();
        setupRepo.listCharges(tenantId).forEach(c -> existing.add(c.code().trim().toUpperCase(java.util.Locale.ROOT)));
        LocalDate today = clock.today(tenantId);
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < req.heads().size(); i++) {
            TemplateHead h = req.heads().get(i);
            String code = h.code().trim();
            if (existing.contains(code.toUpperCase(java.util.Locale.ROOT))) {
                skipped.add(code);
                continue;
            }
            boolean taxable = "taxable".equals(h.taxCategory());
            BigDecimal rate = taxable ? h.taxRatePercent().setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            String sac = h.sacHsn() == null || h.sacHsn().isBlank() ? null : h.sacHsn().trim();
            int order = h.displayOrder() == null ? (i + 1) * 10 : h.displayOrder();
            UUID id;
            try {
                id = setupRepo.insertCharge(tenantId, new com.nabd.hms.organization.setup.dto.ChargeHeadWriteRequest(code, h.name().trim(), h.category().trim(),
                        h.baseAmount(), null, null, sac, rate, false, true, today, null, order), h.taxCategory(), sac);
            } catch (org.springframework.dao.DuplicateKeyException e) { // another request added the code since the read above
                throw new ApiException(HttpStatus.CONFLICT, "CODE_EXISTS", "Code exists", "A charge with code " + code + " was added meanwhile; reload and apply again.",
                        Map.of("code", "CODE_EXISTS", "chargeCode", code));
            }
            pricingLog.logChargeCreated(id, h.baseAmount(), actorId);
            events.publish(tenantId, DomainEventType.CATALOGUE_CHANGED, id, Map.of("chargeHeadId", id, "status", "added")); // config cache invalidation
            created.add(code);
        }
        audit.record(tenantId, "staff", actorId, owner.name(), owner.role(), ip, "setup.price_template_applied", "price_template", t.id(),
                null, Map.of("specialty", t.specialty(), "jurisdiction", t.jurisdiction(), "version", t.version(), "created", created.size(), "skipped", skipped.size()));
        return new BulkResponse(created, skipped);
    }

    private static void validate(TemplateHead h, Set<BigDecimal> rates) {
        String why = null;
        if (h == null || h.code() == null || !CODE.matcher(h.code().trim()).matches()) why = "Each head needs a code of letters, digits, - or _ (up to 32).";
        else if (!text(h.name(), 120)) why = "Each head needs a name of up to 120 characters.";
        else if (!text(h.category(), 40)) why = "Each head needs a category of up to 40 characters.";
        else if (h.baseAmount() == null || h.baseAmount().signum() < 0 || h.baseAmount().stripTrailingZeros().scale() > 2 || h.baseAmount().compareTo(MAX_AMOUNT) > 0)
            why = "Each amount is 0 or more with up to two decimals.";
        else if (h.taxCategory() == null || !TAX_CATEGORIES.contains(h.taxCategory())) why = "taxCategory must be exempt, nil, zero_rated or taxable.";
        else if (h.sacHsn() != null && !h.sacHsn().isBlank() && !SAC_HSN.matcher(h.sacHsn().trim()).matches()) why = "A SAC or HSN code is 4 to 8 digits.";
        else if (h.displayOrder() != null && (h.displayOrder() < 0 || h.displayOrder() > 100000)) why = "displayOrder is 0 to 100000.";
        if (why != null) throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", why);
        if ("taxable".equals(h.taxCategory())) {
            if (h.taxRatePercent() == null || !rates.contains(h.taxRatePercent().setScale(2, java.math.RoundingMode.HALF_UP))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "rate-not-allowed", "Rate not allowed",
                        "A taxable head takes one of the rates " + rates.stream().sorted().map(BigDecimal::toPlainString).toList() + ".");
            }
        } else if (h.taxRatePercent() != null && h.taxRatePercent().signum() != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "rate-not-allowed", "Rate not allowed", "An exempt, nil or zero-rated head carries no rate.");
        }
    }

    private static boolean text(String v, int max) {
        return v != null && !v.isBlank() && v.trim().length() <= max && !CONTROL.matcher(v).find();
    }

    private String market(UUID tenantId) {
        String r = clock.region(tenantId);
        return r == null ? "IN" : r;
    }

    /** FIN-015 AC5: the window is 1..90 days; the change is audited (old and new) and the config cache is invalidated by the policy event. */
    @Transactional
    public com.nabd.hms.organization.setup.dto.PolicyResponse setFollowUpWindow(Integer value, UUID actorId, String ip) {
        UUID tenantId = tenantContext.current();
        if (value == null || value < 1 || value > 90) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "The follow-up window is a whole number of days from 1 to 90.");
        }
        String old = setupRepo.findPolicyValue(tenantId, "follow_up_window_days").orElse(null);
        var res = setup.updatePolicy(tenantId, actorId, "follow_up_window_days", new com.nabd.hms.organization.setup.dto.PolicyWriteRequest(String.valueOf(value)));
        record(tenantId, actorId, ip, "setup.follow_up_window_changed", UUID.fromString(res.id()), Map.of("days", old == null ? "" : old), Map.of("days", String.valueOf(value)));
        return res;
    }

    @Transactional(readOnly = true)
    public ChangePage changes(UUID chargeId, String field, LocalDate from, LocalDate to, String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        int n = Math.max(1, Math.min(limit, 100));
        List<ChangeView> items = views(tenantId, query(tenantId, chargeId, field, from, to, cursor, n + 1));
        String next = null;
        if (items.size() > n) {
            items = items.subList(0, n);
            ChangeView last = items.get(n - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.at() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return new ChangePage(items, next);
    }

    @Transactional(readOnly = true)
    public String changesCsv(UUID chargeId, String field, LocalDate from, LocalDate to) {
        UUID tenantId = tenantContext.current();
        StringBuilder sb = new StringBuilder("at,charge,field,doctor,old_value,new_value,actor\r\n");
        for (ChangeView c : views(tenantId, query(tenantId, chargeId, field, from, to, null, CSV_ROWS))) {
            sb.append(cell(c.at().toString())).append(',').append(cell(c.chargeName())).append(',').append(cell(c.field())).append(',')
                    .append(cell(c.doctorId() == null ? "" : actorName(tenantId, c.doctorId()))).append(',')
                    .append(cell(c.oldValue())).append(',').append(cell(c.newValue())).append(',').append(cell(c.actor())).append("\r\n");
        }
        return sb.toString();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private List<PricingRepository.Change> query(UUID tenantId, UUID chargeId, String field, LocalDate from, LocalDate to, String cursor, int limit) {
        if (field != null && !Set.of("base_amount", "follow_up_amount", "emergency_amount", "doctor_fee", "tax_category", "active").contains(field)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Unknown field filter.");
        }
        var zone = clock.zone(tenantId);
        Instant f = from == null ? null : ClinicClock.startOf(from, zone);
        Instant t = to == null ? null : ClinicClock.startOf(to.plusDays(1), zone); // the "to" day is included
        Instant cAt = null;
        UUID cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                cAt = Instant.parse(p[0]);
                cId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Bad cursor.");
            }
        }
        return repo.changes(tenantId, chargeId, field, f, t, cAt, cId, limit);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private List<ChangeView> views(UUID tenantId, List<PricingRepository.Change> rows) {
        Map<UUID, String> names = repo.chargeNames(tenantId);
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.actorId()));
        var who = actors.findActors(tenantId, ids);
        List<ChangeView> out = new ArrayList<>();
        for (var r : rows) {
            var a = who.get(r.actorId());
            out.add(new ChangeView(r.id(), r.chargeId(), names.getOrDefault(r.chargeId(), ""), r.field(), r.doctorId(), r.oldValue(), r.newValue(),
                    a == null ? "Unknown" : a.name(), r.at()));
        }
        return out;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private FeesResponse view(UUID tenantId, UUID chargeId) {
        var fees = repo.fees(tenantId, chargeId);
        var who = actors.findActors(tenantId, fees.stream().map(PricingRepository.Fee::doctorId).toList());
        return new FeesResponse(fees.stream().map(f -> new FeeView(f.doctorId(), who.containsKey(f.doctorId()) ? who.get(f.doctorId()).name() : "", f.amount())).toList());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private ChargeHeadRow consultationHead(UUID tenantId, UUID chargeId) {
        ChargeHeadRow head = setupRepo.findCharge(tenantId, chargeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "charge-not-found", "Charge not found", "Charge not found"));
        if (!"Consultation".equals(head.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_CONSULTATION_CATEGORY", "Not a consultation charge",
                    "Per-doctor fees and follow-up prices only apply to Consultation charges.");
        }
        return head;
    }

    private String actorName(UUID tenantId, UUID id) {
        return actors.findActor(tenantId, id).map(ActorDirectory.Actor::name).orElse("");
    }

    private void record(UUID tenantId, UUID actorId, String ip, String action, UUID entityId, Object before, Object after) {
        String[] a = actors.findActor(tenantId, actorId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", actorId, a[0], a[1], ip, action, "charge_catalogue", entityId, before, after);
    }

    static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    static String str(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** CSV cell: quoted, and a leading = + - @ is neutralised (spreadsheet formula injection). */
    private static String cell(String v) {
        if (v == null) return "";
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?") ? "'" + v : v;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
