package com.nabd.hms.billing;

import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** REC-043: a non-zero checkout discount needs billing.discount (billing module refundDiscount grant). */
class CheckoutDiscountPermissionApiTest extends ApiTestBase {

    private ResponseEntity<Map> otc(String token, int discount) {
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)),
                "discount", discount)), Map.class);
    }

    private String tokenFor(boolean refundDiscount, String email, String phone) {
        SeededTenant tenant = seedTenant();
        UUID role = seedRole(tenant.id(), "Billing", false,
                new ModuleGrant("billing", true, true, true, false, false, refundDiscount, false));
        return loginAndGetAccessToken(seedStaff(tenant, role, email, phone, false));
    }

    @Test
    void discountWithoutPermissionIsForbidden() {
        ResponseEntity<Map> resp = otc(tokenFor(false, "nodisc@a.com", "+919700000101"), 10);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().contains("discount-not-permitted");
    }

    @Test
    void discountWithPermissionIsAccepted() {
        ResponseEntity<Map> resp = otc(tokenFor(true, "disc@a.com", "+919700000102"), 10);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("discount")).doubleValue()).isEqualTo(10.0);
    }

    @Test
    void zeroDiscountIsAllowedWithoutPermission() {
        assertThat(otc(tokenFor(false, "zero@a.com", "+919700000103"), 0).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sessionPermissionsListCarriesBillingDiscountOnlyWithTheGrant() {
        assertThat(GrantsFlattener.flatten(List.of(new ModuleGrant("billing", true, true, true, false, false, true, false))))
                .contains("billing.discount");
        assertThat(GrantsFlattener.flatten(List.of(new ModuleGrant("billing", true, true, true, false, false, false, false))))
                .doesNotContain("billing.discount");
        assertThat(GrantsFlattener.flatten(List.of(new ModuleGrant("patients", true, true, true, true, true, true, true))))
                .doesNotContain("billing.discount");
    }
}
