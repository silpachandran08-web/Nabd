package com.nabd.hms.packages;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.packages.dto.PackageItemResponse;
import com.nabd.hms.packages.dto.PackageResponse;
import com.nabd.hms.packages.dto.PackageSettingsResponse;
import com.nabd.hms.packages.dto.PackageSettingsWriteRequest;
import com.nabd.hms.packages.dto.PackageWriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.packages.PackageModels.PackageItemRow;
import static com.nabd.hms.packages.PackageModels.PackageRow;

/**
 * NB-150/151: the 6-step package builder and its catalogue lifecycle (draft/on_sale/inactive).
 * A package's price/items/validity are snapshotted onto each PackageInstance at sale time (see
 * PackageInstanceService), so editing or deactivating a package here never touches an instance
 * already sold — that's NB-151's "retiring a package does not affect sold instances" AC, satisfied
 * by the data model rather than a version-history table.
 */
@Service
public class PackageCatalogueService {

    private static final String NOT_FOUND = "The requested package was not found.";

    private static final BigDecimal DEFAULT_PRICE_FLOOR_PERCENT = BigDecimal.valueOf(72);

    private final PackageRepository repo;
    private final TenantContext tenantContext;
    private final com.nabd.hms.billing.api.PackageInvoicing billing;
    private final com.nabd.hms.organization.api.RegionRules regions;
    private final com.nabd.hms.common.ClinicClock clock;
    private final com.nabd.hms.common.AuditService auditService;
    private final com.nabd.hms.iam.api.ActorDirectory actors;

    PackageCatalogueService(PackageRepository repo, TenantContext tenantContext, com.nabd.hms.billing.api.PackageInvoicing billing,
                            com.nabd.hms.organization.api.RegionRules regions, com.nabd.hms.common.ClinicClock clock,
                            com.nabd.hms.common.AuditService auditService, com.nabd.hms.iam.api.ActorDirectory actors) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.billing = billing;
        this.regions = regions;
        this.clock = clock;
        this.auditService = auditService;
        this.actors = actors;
    }

    /** FIN-025: the price-list heads a component can be linked to (billing owns the price list). */
    @Transactional
    public List<com.nabd.hms.packages.dto.ChargeHeadResponse> chargeHeads(UUID tenantId) {
        return billing.chargeHeads(tenantId).stream()
                .map(h -> new com.nabd.hms.packages.dto.ChargeHeadResponse(h.id().toString(), h.code(), h.name(), h.category())).toList();
    }

    /** A component may only be linked to an active head of this clinic's own price list. */
    private void requireOwnHeads(UUID tenantId, PackageWriteRequest req) {
        List<UUID> linked = req.items().stream().map(com.nabd.hms.packages.dto.PackageItemInput::chargeCatalogueId)
                .filter(java.util.Objects::nonNull).toList();
        if (linked.isEmpty()) {
            return;
        }
        var own = billing.chargeHeads(tenantId).stream().map(com.nabd.hms.billing.api.PackageInvoicing.ChargeHead::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!own.containsAll(linked)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unknown-charge-head", "Unknown price-list item",
                    "An included item is linked to a price-list item that is not active in this clinic.");
        }
    }

    @Transactional
    public List<PackageResponse> list(UUID tenantId) {
        tenantContext.set(tenantId);
        BigDecimal floorPercent = floorPercent(tenantId);
        return repo.listPackages(tenantId).stream().map(p -> toResponse(tenantId, p, floorPercent)).toList();
    }

    @Transactional
    public PackageResponse get(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        PackageRow row = repo.findPackage(tenantId, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        return toResponse(tenantId, row, floorPercent(tenantId));
    }

    @Transactional
    public PackageResponse create(UUID tenantId, UUID staffId, PackageWriteRequest req) {
        tenantContext.set(tenantId);
        requireOwnHeads(tenantId, req);
        UUID id = repo.insertPackage(tenantId, staffId, req.name(), req.packageType(), req.speciality(),
                req.description(), req.price(), req.taxInclusive(), req.validityDays(), req.validityStarts(),
                req.graceDaysOrDefault(), req.refundNote());
        repo.replaceItems(tenantId, id, req.items());
        repo.replaceEligibleDoctors(tenantId, id, req.eligibleDoctorIdsOrEmpty());
        return get(tenantId, id);
    }

    @Transactional
    public PackageResponse update(UUID tenantId, UUID id, PackageWriteRequest req) {
        tenantContext.set(tenantId);
        repo.findPackage(tenantId, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        requireOwnHeads(tenantId, req);
        repo.updatePackage(tenantId, id, req.name(), req.packageType(), req.speciality(), req.description(),
                req.price(), req.taxInclusive(), req.validityDays(), req.validityStarts(), req.graceDaysOrDefault(),
                req.refundNote());
        repo.replaceItems(tenantId, id, req.items());
        repo.replaceEligibleDoctors(tenantId, id, req.eligibleDoctorIdsOrEmpty());
        return get(tenantId, id);
    }

    @Transactional
    public PackageResponse activate(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        PackageRow row = repo.findPackage(tenantId, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        BigDecimal listValue = listValue(tenantId, id);
        BigDecimal floor = priceFloor(listValue, floorPercent(tenantId));
        if (row.price().compareTo(floor) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "below-price-floor", "Below the clinic price floor",
                    "This price is short of the floor by " + floor.subtract(row.price()) + ". Activation is " +
                            "disabled and there is no silent override.");
        }
        repo.updateStatus(tenantId, id, "on_sale");
        return get(tenantId, id);
    }

    @Transactional
    public PackageResponse deactivate(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        repo.findPackage(tenantId, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        repo.updateStatus(tenantId, id, "inactive");
        return get(tenantId, id);
    }

    @Transactional
    public PackageSettingsResponse getSettings(UUID tenantId) {
        tenantContext.set(tenantId);
        return toSettings(tenantId, settings(tenantId));
    }

    /** FIN-025 release gate (CEO-07): a GST clinic cannot have the per-component tax split on before the GST opinion's decision id is
     * recorded; the same request may record it. Changing either is audited. */
    @Transactional
    public PackageSettingsResponse updateSettings(UUID tenantId, UUID staffId, PackageSettingsWriteRequest req) {
        tenantContext.set(tenantId);
        PackageModels.PackageSettingsRow before = settings(tenantId);
        boolean enabled = req.taxSplitEnabled() != null ? req.taxSplitEnabled() : before.taxSplitEnabled();
        String decision = req.gstOpinionDecisionId() == null ? before.gstOpinionDecisionId()
                : req.gstOpinionDecisionId().isBlank() ? null : req.gstOpinionDecisionId().trim();
        if (enabled && decision == null && PackageSplit.needsGstOpinion(regions.region(clock.region(tenantId)))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "gst-opinion-required", "GST opinion not recorded",
                    "Per-component tax on packages cannot be turned on until the GST opinion's decision id is recorded.");
        }
        repo.upsertSettings(tenantId, req.priceFloorPercent(), enabled, decision);
        if (enabled != before.taxSplitEnabled() || !java.util.Objects.equals(decision, before.gstOpinionDecisionId())) {
            var actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
            java.util.Map<String, Object> was = new java.util.HashMap<>(), now = new java.util.HashMap<>();
            was.put("taxSplitEnabled", before.taxSplitEnabled());
            was.put("gstOpinionDecisionId", before.gstOpinionDecisionId());
            now.put("taxSplitEnabled", enabled);
            now.put("gstOpinionDecisionId", decision);
            auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "packages.tax_split_changed",
                    "package_settings", tenantId, was, now);
        }
        return toSettings(tenantId, settings(tenantId));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private PackageModels.PackageSettingsRow settings(UUID tenantId) {
        return repo.findSettings(tenantId).orElse(new PackageModels.PackageSettingsRow(DEFAULT_PRICE_FLOOR_PERCENT, false, null));
    }

    private PackageSettingsResponse toSettings(UUID tenantId, PackageModels.PackageSettingsRow s) {
        return new PackageSettingsResponse(s.priceFloorPercent(), s.taxSplitEnabled(), s.gstOpinionDecisionId(),
                PackageSplit.needsGstOpinion(regions.region(clock.region(tenantId))));
    }

    private BigDecimal floorPercent(UUID tenantId) {
        return repo.findSettings(tenantId).map(s -> s.priceFloorPercent()).orElse(DEFAULT_PRICE_FLOOR_PERCENT);
    }

    private BigDecimal priceFloor(BigDecimal listValue, BigDecimal floorPercent) {
        return listValue.multiply(floorPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal listValue(UUID tenantId, UUID packageId) {
        return repo.listItems(tenantId, packageId).stream()
                .map(i -> i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PackageResponse toResponse(UUID tenantId, PackageRow row, BigDecimal floorPercent) {
        List<PackageItemRow> itemRows = repo.listItems(tenantId, row.id());
        List<PackageItemResponse> items = itemRows.stream()
                .map(i -> new PackageItemResponse(i.id().toString(), i.itemType(), i.name(), i.quantity(),
                        i.unitListPrice(), i.taxRatePercent(), i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity())),
                        i.chargeCatalogueId() == null ? null : i.chargeCatalogueId().toString()))
                .toList();
        BigDecimal listValue = itemRows.stream().map(i -> i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal saveAmount = listValue.subtract(row.price()).max(BigDecimal.ZERO);
        BigDecimal savePercent = listValue.signum() == 0 ? BigDecimal.ZERO
                : saveAmount.multiply(BigDecimal.valueOf(100)).divide(listValue, 0, RoundingMode.HALF_UP);
        BigDecimal floor = priceFloor(listValue, floorPercent);
        List<UUID> eligibleDoctorIds = repo.listEligibleDoctorIds(tenantId, row.id());
        String doctorLeaveWarning = eligibleDoctorIds.isEmpty() ? null
                : repo.findEligibleDoctorOnLeaveToday(tenantId, row.id())
                        .map(name -> name + " is on leave today — active packages restricted to them can't be redeemed.")
                        .orElse(null);
        return new PackageResponse(row.id().toString(), row.name(), row.packageType(), row.speciality(),
                row.description(), row.status(), row.price(), row.taxInclusive(), row.validityDays(),
                row.validityStarts(), row.graceDays(), row.refundNote(), listValue, saveAmount, savePercent, floor,
                row.price().compareTo(floor) < 0, eligibleDoctorIds, items, doctorLeaveWarning);
    }
}
