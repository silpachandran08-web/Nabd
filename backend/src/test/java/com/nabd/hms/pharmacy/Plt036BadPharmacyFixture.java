package com.nabd.hms.pharmacy;

import java.util.UUID;

/** PLT-036 fixture for Plt036SingleWriterArchTest: a pharmacy class that depends on billing and implements another module's write port. */
public class Plt036BadPharmacyFixture implements com.nabd.hms.nursing.api.ProcedureBilling {
    com.nabd.hms.billing.api.PackageRedemption redemption;

    @Override
    public void markVisitBilled(UUID tenantId, UUID visitId) {
    }
}
