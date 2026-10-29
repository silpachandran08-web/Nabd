package com.nabd.hms.pharmacy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-003 AC1: only com.nabd.hms.pharmacy writes charge_catalogue.stock_qty, pharmacy_stock_movements or stock_batches. */
class StockWriteOwnershipTest {

    private static final Pattern WRITE = Pattern.compile(
            "(insert\\s+into|update|delete\\s+from)\\s+(pharmacy_stock_movements|stock_batches)\\b"
                    + "|update\\s+charge_catalogue\\s+set\\b[^;\"]*\\bstock_qty\\s*="
                    + "|insert\\s+into\\s+charge_catalogue\\s*\\([^)]*\\bstock_qty\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Joins adjacent string literals ("a" + "b") so a statement split over lines is scanned as one. */
    static boolean writesStock(String javaSource) {
        return WRITE.matcher(javaSource.replaceAll("\"\\s*\\+\\s*\"", "")).find();
    }

    @Test
    void scannerFlagsAWriteAndIgnoresReads() {
        assertThat(writesStock("jdbc.update(\"UPDATE charge_catalogue SET \" +\n \"stock_qty = stock_qty - ?\")")).isTrue();
        assertThat(writesStock("jdbc.update(\"INSERT INTO pharmacy_stock_movements (a) VALUES (?)\")")).isTrue();
        assertThat(writesStock("jdbc.update(\"UPDATE stock_batches SET x = 1\")")).isTrue();
        assertThat(writesStock("jdbc.query(\"SELECT stock_qty FROM charge_catalogue WHERE id = ?\")")).isFalse();
        assertThat(writesStock("jdbc.update(\"UPDATE charge_catalogue SET name = ?, updated_at = now()\")")).isFalse();
    }

    @Test
    void noClassOutsidePharmacyWritesStock() throws IOException {
        Path root = Files.exists(Path.of("src/main/java")) ? Path.of("src/main/java") : Path.of("backend/src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("com/nabd/hms"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.toString().replace('\\', '/').contains("/com/nabd/hms/pharmacy/")) {
                    continue;
                }
                if (writesStock(Files.readString(f))) {
                    offenders.add(f.toString());
                }
            }
        }
        assertThat(offenders).as("stock is written only through pharmacy.api.StockCommands").isEmpty();
    }
}
