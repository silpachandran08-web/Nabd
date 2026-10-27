package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.CheckoutPrefillResponse;
import com.nabd.hms.clinical.api.SignedRxLines;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-018: which of the visit's signed Rx lines this clinic dispenses itself. A line is in-house when a stocked, active, in-house
 * catalogue item matches its coded drug (or, for a free-text line, its name); anything else is "buy outside" and not offered.
 * Lines already billed (non-void invoices) are reduced by what was dispensed; a line with nothing left is dropped.
 */
@Service
public class CheckoutPrefill {

    private record Item(String code, String name, int stock, String drugId) {
    }

    private final CheckoutRepository repo;
    private final SignedRxLines rx;
    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    CheckoutPrefill(CheckoutRepository repo, SignedRxLines rx, JdbcTemplate jdbc, TenantContext tenant) {
        this.repo = repo;
        this.rx = rx;
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    public CheckoutPrefillResponse prefill(UUID queueEntryId) {
        UUID tenantId = tenant.current();
        repo.findQueueEntryContext(tenantId, queueEntryId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        String mode = repo.findPharmacyMode(tenantId).orElse("external");
        List<SignedRxLines.Line> lines = rx.forVisit(queueEntryId);
        if ("external".equals(mode) || lines.isEmpty()) {
            return new CheckoutPrefillResponse(List.of());
        }
        String[] drugIds = lines.stream().map(SignedRxLines.Line::drugId).filter(java.util.Objects::nonNull).distinct().toArray(String[]::new);
        String[] names = lines.stream().map(l -> norm(l.drugName())).distinct().toArray(String[]::new);
        List<Item> items = jdbc.query("SELECT c.code, c.name, c.stock_qty, c.drug_id FROM charge_catalogue c " +
                        "LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id " +
                        "WHERE c.tenant_id = ? AND c.active AND c.stock_qty IS NOT NULL AND COALESCE(p.in_house, true) " +
                        "AND (c.drug_id = ANY (?) OR lower(btrim(c.name)) = ANY (?)) ORDER BY c.code",
                (rs, i) -> new Item(rs.getString("code"), rs.getString("name"), rs.getInt("stock_qty"), rs.getString("drug_id")),
                tenantId, drugIds, names);
        Map<UUID, BigDecimal> dispensed = new HashMap<>();
        jdbc.query("SELECT li.prescription_item_id, SUM(li.quantity) AS qty FROM invoice_line_items li JOIN invoices i ON i.id = li.invoice_id " +
                        "AND i.tenant_id = li.tenant_id WHERE li.tenant_id = ? AND li.prescription_item_id = ANY (?) AND i.status <> 'void' " +
                        "GROUP BY li.prescription_item_id",
                rs -> {
                    dispensed.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }, tenantId, lines.stream().map(SignedRxLines.Line::prescriptionItemId).toArray(UUID[]::new));
        List<CheckoutPrefillResponse.Line> out = new ArrayList<>();
        for (SignedRxLines.Line l : lines) {
            if (l.quantity() == null) continue; // no prescribed quantity: nothing to dispense against (same fail-safe as the Rx gate)
            int qty = l.quantity().subtract(dispensed.getOrDefault(l.prescriptionItemId(), BigDecimal.ZERO)).setScale(0, RoundingMode.CEILING).intValue();
            if (qty <= 0) continue;
            Item hit = items.stream().filter(i -> l.drugId() != null && l.drugId().equals(i.drugId())).findFirst()
                    .orElseGet(() -> items.stream().filter(i -> norm(i.name()).equals(norm(l.drugName()))).findFirst().orElse(null));
            if (hit != null) out.add(new CheckoutPrefillResponse.Line(hit.code(), hit.name(), qty, l.prescriptionItemId(), hit.stock()));
        }
        return new CheckoutPrefillResponse(out);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
