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

    private static final BigDecimal DEFAULT_PRICE_FLOOR_PERCENT = BigDecimal.valueOf(72);

    private final PackageRepository repo;
    private final TenantContext tenantContext;

    PackageCatalogueService(PackageRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
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
        PackageRow row = repo.findPackage(tenantId, id).orElseThrow(this::notFound);
        return toResponse(tenantId, row, floorPercent(tenantId));
    }

    @Transactional
    public PackageResponse create(UUID tenantId, UUID staffId, PackageWriteRequest req) {
        tenantContext.set(tenantId);
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
        repo.findPackage(tenantId, id).orElseThrow(this::notFound);
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
        PackageRow row = repo.findPackage(tenantId, id).orElseThrow(this::notFound);
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
        repo.findPackage(tenantId, id).orElseThrow(this::notFound);
        repo.updateStatus(tenantId, id, "inactive");
        return get(tenantId, id);
    }

    @Transactional
    public PackageSettingsResponse getSettings(UUID tenantId) {
        tenantContext.set(tenantId);
        return new PackageSettingsResponse(floorPercent(tenantId));
    }

    @Transactional
    public PackageSettingsResponse updateSettings(UUID tenantId, PackageSettingsWriteRequest req) {
        tenantContext.set(tenantId);
        repo.upsertSettings(tenantId, req.priceFloorPercent());
        return new PackageSettingsResponse(req.priceFloorPercent());
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
                        i.unitListPrice(), i.taxRatePercent(), i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity()))))
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

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested package was not found.");
    }
}
