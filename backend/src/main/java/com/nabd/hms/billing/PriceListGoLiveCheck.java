package com.nabd.hms.billing;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.GoLiveCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ADM-011 blocker: the price list has at least one active charge. */
@Component
class PriceListGoLiveCheck implements GoLiveCheck {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    PriceListGoLiveCheck(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public boolean blocker() {
        return true;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Result evaluate() {
        boolean ok = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM charge_catalogue WHERE tenant_id = ? AND active)", Boolean.class, tenant.current()));
        return new Result("price_list", ok, "/setup/prices", null);
    }
}
