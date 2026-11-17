package com.nabd.hms.packages;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.PackageCards;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** COM-044: the package card behind the secure link (sessions used/left, expiry, paid/due, savings). Read in the caller's transaction. */
@Service
class PackageCardService implements PackageCards {

    private final PackageRepository packages;
    private final PackageMessagingRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    PackageCardService(PackageRepository packages, PackageMessagingRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.packages = packages;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    @Transactional // joins the secure-link open transaction
    public Optional<Card> card(UUID instanceId, UUID patientId) {
        UUID tenantId = tenantContext.current();
        PackageModels.InstanceRow row = packages.findInstance(tenantId, instanceId).filter(r -> r.patientId().equals(patientId)).orElse(null);
        PackageMessagingRepository.Money money = row == null ? null : repo.money(tenantId, instanceId).orElse(null);
        if (money == null) {
            return Optional.empty();
        }
        List<PackageModels.InstanceItemRow> rows = packages.listInstanceItems(tenantId, instanceId);
        List<Item> items = rows.stream().map(i -> new Item(i.name(), i.quantityConsumed(), i.quantityTotal())).toList();
        BigDecimal listValue = rows.stream().map(i -> i.unitListPrice().multiply(BigDecimal.valueOf(i.quantityTotal()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new Card(row.packageName(), PackageInstanceService.effectiveStatus(row, clock.today(tenantId)), items,
                items.stream().mapToInt(Item::used).sum(), items.stream().mapToInt(Item::total).sum(), row.validityEnd(),
                row.validityEnd() == null ? null : row.validityEnd().plusDays(row.graceDays()), money.paid(),
                money.total().subtract(money.paid()).max(BigDecimal.ZERO), listValue.subtract(row.soldPrice()).max(BigDecimal.ZERO), money.currency()));
    }
}
