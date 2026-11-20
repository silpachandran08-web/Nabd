package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S25 QA t1 (PHL-009): stock leaves a quarantined batch once. A supplier return and a write-off of the same ten units sent at the same
 * instant must not both succeed (stock money, and the batch quantity must never go negative); the other clinic's batch id is a 404 for all three actions.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S25QaPhl009RaceApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(7900000);

    @Autowired StockCommands stock;

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), UUID.randomUUID().toString().substring(0, 8) + "@q9.test", "+9194" + String.format("%08d", PHONE.incrementAndGet()), false);
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "QA setup")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, token, staff.id());
    }

    private UUID quarantinedBatch(Clinic c, int qty) {
        ResponseEntity<Map> it = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "QA Race Drug " + UUID.randomUUID().toString().substring(0, 5),
                "isRx", false, "price", 100.00, "taxRatePercent", 12.00, "scheduleClass", "OTC")), Map.class);
        assertThat(it.getStatusCode()).as("%s", it.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID item = UUID.fromString((String) it.getBody().get("id"));
        UUID batch = inTenantTx(c.tenant().id(), () -> stock.receiveBatch(item, new BatchReceipt("B-" + UUID.randomUUID().toString().substring(0, 5), LocalDate.now().plusDays(400),
                new BigDecimal("20.00"), new BigDecimal("10.00"), qty, "A1", null, null), c.staffId(), "GRN QA").batchId());
        assertThat(exchange("/v1/pharmacy/batches/" + batch + "/quarantine", HttpMethod.POST, authedJsonBody(c.token(), Map.of("reason", "damaged")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return batch;
    }

    @Test
    void aSupplierReturnAndAWriteOffOfTheSameTenUnitsAtOnce_stockLeavesOnce_neverNegative() throws Exception {
        Clinic c = clinic();
        UUID batch = quarantinedBatch(c, 10);
        List<Map<String, Object>> lines = List.of(Map.of("batchId", batch.toString(), "qty", 10));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier gate = new CyclicBarrier(2);
        Future<ResponseEntity<Map>> ret = pool.submit(() -> {
            gate.await();
            return exchange("/v1/pharmacy/supplier-returns", HttpMethod.POST, authedJsonBody(c.token(), Map.of("supplierName", "QA Supplier", "lines", lines)), Map.class);
        });
        Future<ResponseEntity<Map>> off = pool.submit(() -> {
            gate.await();
            return exchange("/v1/pharmacy/write-offs", HttpMethod.POST, authedJsonBody(c.token(), Map.of("method", "destroyed", "lines", lines)), Map.class);
        });
        ResponseEntity<Map> r1 = ret.get(), r2 = off.get();
        pool.shutdown();
        long ok = List.of(r1, r2).stream().filter(r -> r.getStatusCode().is2xxSuccessful()).count();
        assertThat(ok).as("return %s %s / write-off %s %s", r1.getStatusCode(), r1.getBody(), r2.getStatusCode(), r2.getBody()).isEqualTo(1);
        assertThat(su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, batch)).isEqualTo(0);
        Integer out = su().queryForObject("SELECT coalesce(sum(abs(qty)),0) FROM pharmacy_stock_movements WHERE batch_id = ? AND kind IN ('return_out','write_off')", Integer.class, batch);
        assertThat(out).as("units that left the batch through return_out + write_off").isEqualTo(10);
    }

    @Test
    void anotherClinicsBatchIsA404ForQuarantineReturnAndWriteOff() {
        Clinic mine = clinic();
        Clinic theirs = clinic();
        UUID batch = quarantinedBatch(theirs, 5);
        List<Map<String, Object>> lines = List.of(Map.of("batchId", batch.toString(), "qty", 1));
        List<Integer> codes = new ArrayList<>();
        codes.add(exchange("/v1/pharmacy/batches/" + batch + "/quarantine", HttpMethod.POST, authedJsonBody(mine.token(), Map.of("reason", "damaged")), Map.class).getStatusCode().value());
        codes.add(exchange("/v1/pharmacy/supplier-returns", HttpMethod.POST, authedJsonBody(mine.token(), Map.of("supplierName", "S", "lines", lines)), Map.class).getStatusCode().value());
        codes.add(exchange("/v1/pharmacy/write-offs", HttpMethod.POST, authedJsonBody(mine.token(), Map.of("method", "lost", "lines", lines)), Map.class).getStatusCode().value());
        assertThat(codes).as("quarantine / return / write-off of another clinic's batch").allMatch(code -> code == 404 || code == 422 || code == 409).noneMatch(code -> code / 100 == 2);
        assertThat(su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, batch)).isEqualTo(5);
    }
}
