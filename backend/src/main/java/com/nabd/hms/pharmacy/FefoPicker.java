package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.api.StockCommands.Pick;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** PHL-006: the pure FEFO rule (first expiry first out). Deterministic: expiry ascending, unknown expiry last, ties by batch id. */
final class FefoPicker {

    record Batch(UUID id, String batchNo, LocalDate expiryDate, java.math.BigDecimal mrp, java.math.BigDecimal purchaseRate,
                 int qtyOnHand, String status, String location) {
        boolean sellable(LocalDate today) {
            return "active".equals(status) && qtyOnHand > 0 && (expiryDate == null || !expiryDate.isBefore(today));
        }
    }

    /** Result of a plan: the picks, or how many units are sellable when there are too few. */
    record Plan(List<Pick> picks, int sellable) {
        boolean enough() {
            return picks != null;
        }
    }

    static final Comparator<Batch> ORDER = Comparator
            .comparing(Batch::expiryDate, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Batch::id);

    private FefoPicker() {
    }

    static Plan plan(List<Batch> all, int qty, Integer courseDays, LocalDate today) {
        List<Batch> usable = all.stream().filter(b -> b.sellable(today)).sorted(ORDER).toList();
        int total = usable.stream().mapToInt(Batch::qtyOnHand).sum();
        if (total < qty) {
            return new Plan(null, total);
        }
        LocalDate courseEnd = courseDays == null ? null : today.plusDays(courseDays);
        List<Batch> source = usable;
        if (courseEnd != null) {
            List<Batch> whole = usable.stream().filter(b -> b.expiryDate() == null || !b.expiryDate().isBefore(courseEnd)).toList();
            if (whole.stream().mapToInt(Batch::qtyOnHand).sum() >= qty) {
                source = whole;
            }
        }
        List<Pick> picks = new ArrayList<>();
        int left = qty;
        for (Batch b : source) {
            if (left == 0) {
                break;
            }
            int take = Math.min(left, b.qtyOnHand());
            boolean mid = courseEnd != null && b.expiryDate() != null && b.expiryDate().isBefore(courseEnd);
            picks.add(new Pick(b.id(), take, b.expiryDate(), mid ? "expires-mid-course" : null));
            left -= take;
        }
        return new Plan(picks, total);
    }
}
