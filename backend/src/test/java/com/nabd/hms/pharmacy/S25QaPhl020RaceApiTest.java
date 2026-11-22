package com.nabd.hms.pharmacy;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S25 QA t1 (PHL-020 / PHL-019): races on the counter sale. A paper prescription is a clinical limit and a stock count is money:
 * two cashiers selling at the same instant must not both get the last of either.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S25QaPhl020RaceApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 500_000);

    private record Ctx(SeededTenant tenant, String token) {
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "qa25r" + SEQ.incrementAndGet() + "@a.com", "+9197" + String.format("%08d", SEQ.incrementAndGet()), false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "QA setup")), Map.class);
        return new Ctx(tenant, token);
    }

    private String[] item(Ctx c, String name, boolean rx, int stock) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "isRx", rx, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", stock));
        if (rx) body.put("scheduleClass", "IN_H");
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("item %s", r.getBody()).isTrue();
        return new String[]{(String) r.getBody().get("id"), (String) r.getBody().get("code")};
    }

    private String paperRxLine(Ctx c, String itemId, int qty) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/paper-prescriptions", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "prescriberName", "Dr QA Prescriber", "prescriberRegNo", "REG-" + SEQ.incrementAndGet(), "rxDate", LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                "lines", List.of(Map.of("itemId", itemId, "qty", qty)), "duplicateCopyRetained", false)), Map.class);
        assertThat(r.getStatusCode().value()).as("paper rx %s", r.getBody()).isEqualTo(201);
        return (String) ((Map<?, ?>) ((List<?>) r.getBody().get("lines")).get(0)).get("paperRxLineId");
    }

    private ResponseEntity<Map> sale(Ctx c, String code, int qty, String paperRxLineId) {
        Map<String, Object> line = new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
        if (paperRxLineId != null) line.put("paperRxLineId", paperRxLineId);
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(line))), Map.class);
    }

    private List<ResponseEntity<Map>> parallel(int n, java.util.function.IntFunction<ResponseEntity<Map>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier gate = new CyclicBarrier(n);
        List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            Callable<ResponseEntity<Map>> job = () -> {
                gate.await();
                return call.apply(k);
            };
            fs.add(pool.submit(job));
        }
        List<ResponseEntity<Map>> out = new ArrayList<>();
        for (Future<ResponseEntity<Map>> f : fs) out.add(f.get());
        pool.shutdown();
        return out;
    }

    @Test
    void twoCashiersSellingTheWholePaperRxAtOnce_exactlyOneSale() throws Exception {
        Ctx c = ctx();
        String[] rx = item(c, "QA Race Rx A", true, 100);
        String line = paperRxLine(c, rx[0], 10);
        List<ResponseEntity<Map>> rs = parallel(2, i -> sale(c, rx[1], 10, line));
        long ok = rs.stream().filter(r -> r.getStatusCode().value() == 200).count();
        assertThat(ok).as("sales that went through: %s", rs.stream().map(r -> r.getStatusCode() + " " + r.getBody()).toList()).isEqualTo(1);
        Integer sold = su().queryForObject("SELECT coalesce(sum(quantity),0) FROM invoice_line_items WHERE tenant_id = ? AND paper_rx_line_id = ?::uuid", Integer.class, c.tenant().id(), line);
        assertThat(sold).as("units sold against a paper Rx of 10").isLessThanOrEqualTo(10);
    }

    @Test
    void fourSalesOfThreeUnitsAgainstAPaperRxOfTen_neverMoreThanTenUnits() throws Exception {
        Ctx c = ctx();
        String[] rx = item(c, "QA Race Rx B", true, 100);
        String line = paperRxLine(c, rx[0], 10);
        parallel(4, i -> sale(c, rx[1], 3, line));
        Integer sold = su().queryForObject("SELECT coalesce(sum(quantity),0) FROM invoice_line_items WHERE tenant_id = ? AND paper_rx_line_id = ?::uuid", Integer.class, c.tenant().id(), line);
        assertThat(sold).as("units sold against a paper Rx of 10").isLessThanOrEqualTo(10);
    }

    @Test
    void twoCashiersSellingTheLastUnitAtOnce_exactlyOneSale_andStockNeverNegative() throws Exception {
        Ctx c = ctx();
        String[] otc = item(c, "QA Race Otc", false, 1);
        List<ResponseEntity<Map>> rs = parallel(2, i -> sale(c, otc[1], 1, null));
        long ok = rs.stream().filter(r -> r.getStatusCode().value() == 200).count();
        assertThat(ok).as("sales of the last unit: %s", rs.stream().map(r -> r.getStatusCode() + " " + r.getBody()).toList()).isEqualTo(1);
        Integer stock = su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE tenant_id = ? AND code = ?", Integer.class, c.tenant().id(), otc[1]);
        assertThat(stock).isGreaterThanOrEqualTo(0);
    }
}
