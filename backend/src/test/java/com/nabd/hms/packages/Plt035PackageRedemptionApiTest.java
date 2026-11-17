package com.nabd.hms.packages;

import com.nabd.hms.billing.api.PackageRedemption;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-035: package redemption from billing goes through billing.api.PackageRedemption, on the same locked path as the desk's Redeem. */
class Plt035PackageRedemptionApiTest extends ApiTestBase {

    @Autowired PackageRedemption redemption;

    @SuppressWarnings("unchecked")
    @Test
    void redemptionThroughBillingApiDecrementsOnceAndTwoConcurrentRedeemsOfTheLastSessionLetOneThrough() throws Exception {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "plt035@a.com", "+919500003501", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Plt Redeem", "phone", "+919980003501", "dob", "1985-01-01", "gender", "female")), Map.class).getBody().get("id");
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Two facials", "packageType", "combination", "speciality", "Dermatology", "description", "desc", "price", 1800.00,
                "taxInclusive", false, "validityDays", 90, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Facial", "quantity", 2, "unitListPrice", 1000.00, "taxRatePercent", 18.0)))), Map.class);
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageId = (String) pkg.getBody().get("id");
        assertThat(exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> sold = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class);
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID itemId = UUID.fromString(((List<Map<String, Object>>) sold.getBody().get("items")).get(0).get("id").toString());

        PackageRedemption.Redeemed first = inTenantTx(tenant.id(), () -> redemption.redeem(tenant.id(), staff.id(), itemId));
        assertThat(first.quantityConsumed()).isEqualTo(1);
        assertThat(first.quantityTotal()).isEqualTo(2);
        assertThat(first.instanceId().toString()).isEqualTo(sold.getBody().get("id"));

        // the last session, redeemed twice at once: exactly one wins, the other is refused, never 3 of 2
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            outcomes.add(pool.submit(() -> {
                go.await();
                try {
                    return inTenantTx(tenant.id(), () -> redemption.redeem(tenant.id(), staff.id(), itemId)).instanceStatus();
                } catch (ApiException e) {
                    return "refused:" + e.getMessage();
                }
            }));
        }
        go.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : outcomes) results.add(f.get());
        pool.shutdown();
        assertThat(results).filteredOn(r -> r.startsWith("refused")).hasSize(1);
        assertThat(results).filteredOn(r -> !r.startsWith("refused")).containsExactly("completed");
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT quantity_consumed FROM package_instance_items WHERE id = ?", Integer.class, itemId)))
                .isEqualTo(2);
    }
}
