package com.nabd.hms.packages;

import com.nabd.hms.billing.api.PackageRedemption;
import com.nabd.hms.packages.dto.InstanceItemResponse;
import com.nabd.hms.packages.dto.InstanceResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** PLT-035: billing.api.PackageRedemption on the same locked redeem path as POST /v1/packages/instances/items/{id}/redeem. */
@Component
class BillingPackageRedemption implements PackageRedemption {

    private final PackageInstanceService instances;

    BillingPackageRedemption(PackageInstanceService instances) {
        this.instances = instances;
    }

    @Override
    public Redeemed redeem(UUID tenantId, UUID staffId, UUID instanceItemId) {
        InstanceResponse r = instances.redeem(tenantId, staffId, instanceItemId);
        InstanceItemResponse item = r.items().stream().filter(i -> i.id().equals(instanceItemId.toString())).findFirst().orElseThrow();
        return new Redeemed(UUID.fromString(r.id()), instanceItemId, item.quantityConsumed(), item.quantityTotal(), r.status());
    }
}
