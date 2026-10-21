package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.tenant.dto.TenantRegionResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * OPS-061: the region is chosen at provisioning and cannot change once the tenant exists. The database trigger is the
 * rule; this only turns its refusal into 409 REGION_IMMUTABLE. The transaction is a TransactionTemplate so the
 * refused statement is rolled back before the 409 is built.
 */
@Service
public class TenantRegionService {

    private final TenantRegionRepository repo;
    private final TransactionTemplate tx;

    TenantRegionService(TenantRegionRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
    }

    public TenantRegionResponse change(UUID tenantId, String regionCode) {
        try {
            // the refused UPDATE rolls its own transaction back; the 409 is built outside it
            tx.executeWithoutResult(s -> {
                repo.findRegion(tenantId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                        "The requested tenant was not found."));
                repo.setRegion(tenantId, regionCode);
            });
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && "NR001".equals(sql.getSQLState())) {
                throw new ApiException(HttpStatus.CONFLICT, "region-immutable", "Region is immutable",
                        "A clinic's region is chosen at provisioning and cannot be changed.", Map.of("code", "REGION_IMMUTABLE"));
            }
            throw e;
        }
        return new TenantRegionResponse(tenantId, regionCode);
    }
}
