package com.nabd.hms.billing.tax;

import com.nabd.hms.billing.CreditNoteMath;
import com.nabd.hms.billing.CreditNoteMath.Remaining;
import com.nabd.hms.billing.tax.TaxEngine.Cart;
import com.nabd.hms.billing.tax.TaxEngine.Category;
import com.nabd.hms.billing.tax.TaxEngine.Component;
import com.nabd.hms.billing.tax.TaxEngine.Line;
import com.nabd.hms.billing.tax.TaxEngine.Result;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-010: the golden-file harness shared by every jurisdiction. Each directory under src/test/resources/golden holds cases.json
 * (JSON carts -> expected per-line taxable value, tax components, totals, printed fields; credit-note and checkpoint cases too)
 * and signoff.json. A case that differs by one minor unit fails the build. The chartered accountant's review state is checked
 * by scripts/ci/golden_pack.py; the PDF text of rendered invoices joins the pack when FIN-012's renderer is in the tree.
 */
class GoldenPackTest {

    private static final Path ROOT = Path.of("src/test/resources/golden");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path p) throws IOException {
        return JSON.readValue(Files.readString(p), Map.class);
    }

    private static List<Path> packDirs() throws IOException {
        try (Stream<Path> s = Files.list(ROOT)) {
            return s.filter(d -> Files.isRegularFile(d.resolve("cases.json"))).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static long l(Object o) {
        return ((Number) o).longValue();
    }

    private static TaxEngine engineFor(Map<String, Object> pack) {
        return new RuleBasedTaxEngine(switch ((String) pack.get("taxRegime")) {
            case "GST" -> new IndiaGst();
            case "VAT" -> new FlatVat();
            default -> throw new IllegalArgumentException("unknown taxRegime " + pack.get("taxRegime"));
        });
    }

    private static Cart cart(Map<String, Object> c) {
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> x : list(c.get("lines"))) {
            lines.add(new Line((String) x.get("key"), l(x.get("grossMinor")), Category.valueOf((String) x.get("category")),
                    new BigDecimal((String) x.get("ratePercent")), (Boolean) x.get("discountable"), (Boolean) x.get("inclusive"), (Boolean) x.get("interState")));
        }
        return new Cart(((Number) c.get("minorUnits")).intValue(), (Boolean) c.get("wholeUnitRoundOff"), (Boolean) c.get("billOfSupply"),
                l(c.get("invoiceDiscountMinor")), lines);
    }

    private static void assertComponents(String where, List<Component> got, List<Map<String, Object>> want) {
        assertThat(got).as(where + " tax component count").hasSameSizeAs(want);
        for (int i = 0; i < want.size(); i++) {
            Component g = got.get(i);
            Map<String, Object> w = want.get(i);
            assertThat(g.type()).as(where + " component type").isEqualTo(w.get("type"));
            assertThat(g.ratePercent()).as(where + " " + g.type() + " rate").isEqualByComparingTo(new BigDecimal((String) w.get("ratePercent")));
            assertThat(g.taxableMinor()).as(where + " " + g.type() + " taxable").isEqualTo(l(w.get("taxableMinor")));
            assertThat(g.amountMinor()).as(where + " " + g.type() + " amount").isEqualTo(l(w.get("amountMinor")));
        }
    }

    private static void assertInvoice(String where, Result r, Map<String, Object> want) {
        List<Map<String, Object>> wl = list(want.get("lines"));
        assertThat(r.lines()).as(where + " line count").hasSameSizeAs(wl);
        for (int i = 0; i < wl.size(); i++) {
            var g = r.lines().get(i);
            assertThat(g.key()).isEqualTo(wl.get(i).get("key"));
            assertThat(g.discountMinor()).as(where + " " + g.key() + " discount").isEqualTo(l(wl.get(i).get("discountMinor")));
            assertThat(g.taxableMinor()).as(where + " " + g.key() + " taxable").isEqualTo(l(wl.get(i).get("taxableMinor")));
            assertComponents(where + " " + g.key(), g.taxes(), list(wl.get(i).get("taxes")));
        }
        assertThat(r.subtotalMinor()).as(where + " subtotal").isEqualTo(l(want.get("subtotalMinor")));
        assertThat(r.discountMinor()).as(where + " discount").isEqualTo(l(want.get("discountMinor")));
        assertThat(r.taxMinor()).as(where + " tax").isEqualTo(l(want.get("taxMinor")));
        assertThat(r.roundOffMinor()).as(where + " round-off").isEqualTo(l(want.get("roundOffMinor")));
        assertThat(r.totalMinor()).as(where + " total").isEqualTo(l(want.get("totalMinor")));
        assertThat(r.subtotalMinor() - r.discountMinor() + r.taxMinor()).as(where + " identity subtotal - discount + tax = total - roundOff")
                .isEqualTo(r.totalMinor() - r.roundOffMinor());
    }

    /** What the invoice prints besides the lines: its title, the tax summary by component and rate, and whether a round-off line shows. */
    private static void assertPrinted(String where, Cart cart, Result r, Map<String, Object> want) {
        assertThat(cart.billOfSupply() ? "Bill of Supply" : "Tax Invoice").as(where + " document title").isEqualTo(want.get("documentTitle"));
        Map<String, long[]> sum = new TreeMap<>();
        for (var lr : r.lines()) {
            for (Component c : lr.taxes()) {
                long[] s = sum.computeIfAbsent(c.type() + "|" + String.format(java.util.Locale.ROOT, "%.2f", c.ratePercent()), k -> new long[2]);
                s[0] += c.taxableMinor();
                s[1] += c.amountMinor();
            }
        }
        List<Map<String, Object>> w = list(want.get("taxSummary"));
        assertThat(sum).as(where + " tax summary rows").hasSameSizeAs(w);
        int i = 0;
        for (var e : sum.entrySet()) {
            assertThat(e.getKey()).as(where + " summary key").isEqualTo(w.get(i).get("type") + "|" + w.get(i).get("ratePercent"));
            assertThat(e.getValue()[0]).as(where + " summary taxable").isEqualTo(l(w.get(i).get("taxableMinor")));
            assertThat(e.getValue()[1]).as(where + " summary tax").isEqualTo(l(w.get(i).get("taxMinor")));
            i++;
        }
        assertThat(r.roundOffMinor() != 0).as(where + " round-off line shown").isEqualTo(want.get("roundOffLine"));
    }

    private static void runCase(Map<String, Object> pack, Map<String, Object> c) {
        TaxEngine engine = engineFor(pack);
        String id = (String) c.get("id");
        switch ((String) c.get("kind")) {
            case "invoice" -> {
                Cart cart = cart(map(c.get("cart")));
                Result r = engine.compute(cart);
                assertInvoice(id, r, map(c.get("expected")));
                assertPrinted(id, cart, r, map(c.get("printed")));
            }
            case "checkpoints" -> {
                for (Map<String, Object> cp : list(c.get("checkpoints"))) {
                    Cart cart = cart(map(cp.get("cart")));
                    Result r = engine.compute(cart);
                    assertInvoice(id, r, map(cp.get("expected")));
                    assertPrinted(id, cart, r, map(cp.get("printed")));
                }
            }
            case "credit_note" -> {
                Map<String, Object> cartJson = map(c.get("cart"));
                Result inv = engine.compute(cart(cartJson));
                assertThat(inv.totalMinor()).as(id + " invoice total").isEqualTo(l(c.get("invoiceTotalMinor")));
                List<Remaining> open = new ArrayList<>();
                List<Map<String, Object>> cl = list(cartJson.get("lines"));
                for (int i = 0; i < cl.size(); i++) {
                    var lr = inv.lines().get(i);
                    open.add(new Remaining((String) cl.get(i).get("key"), ((Number) cl.get(i).get("quantity")).intValue(), lr.taxableMinor(), lr.taxes()));
                }
                long roundOffLeft = inv.roundOffMinor();
                long refunded = 0;
                List<Map<String, Object>> reversals = list(c.get("reversals"));
                List<Map<String, Object>> expected = list(c.get("expected"));
                assertThat(expected).as(id + " one expected note per reversal").hasSameSizeAs(reversals);
                for (int n = 0; n < reversals.size(); n++) {
                    Map<String, Object> sel = reversals.get(n);
                    CreditNoteMath.Note note;
                    if (sel.containsKey("lines")) {
                        List<CreditNoteMath.Reversal> revs = new ArrayList<>();
                        for (Map<String, Object> x : list(sel.get("lines"))) {
                            Remaining r = open.stream().filter(o -> o.lineKey().equals(x.get("key"))).findFirst().orElseThrow();
                            revs.add(CreditNoteMath.byQuantity(r, ((Number) x.get("quantity")).intValue()));
                        }
                        note = CreditNoteMath.ofQuantities(open, revs, roundOffLeft);
                    } else {
                        note = CreditNoteMath.byAmount(open, l(sel.get("amountMinor")), roundOffLeft);
                    }
                    assertNote(id + " note " + (n + 1), note, map(expected.get(n)));
                    for (var rev : note.lines()) {
                        for (int i = 0; i < open.size(); i++) {
                            if (open.get(i).lineKey().equals(rev.lineKey())) open.set(i, CreditNoteMath.after(open.get(i), rev));
                        }
                    }
                    roundOffLeft -= note.roundOffMinor();
                    refunded += note.totalMinor();
                }
                assertThat(refunded).as(id + " credit notes never exceed the invoice").isLessThanOrEqualTo(inv.totalMinor());
            }
            default -> throw new IllegalArgumentException("unknown case kind " + c.get("kind"));
        }
    }

    private static void assertNote(String where, CreditNoteMath.Note note, Map<String, Object> want) {
        List<Map<String, Object>> wl = list(want.get("lines"));
        assertThat(note.lines()).as(where + " reversed line count").hasSameSizeAs(wl);
        for (int i = 0; i < wl.size(); i++) {
            var g = note.lines().get(i);
            assertThat(g.lineKey()).isEqualTo(wl.get(i).get("key"));
            assertThat(g.quantity()).as(where + " " + g.lineKey() + " quantity").isEqualTo(((Number) wl.get(i).get("quantity")).intValue());
            assertThat(g.taxableMinor()).as(where + " " + g.lineKey() + " taxable").isEqualTo(l(wl.get(i).get("taxableMinor")));
            assertComponents(where + " " + g.lineKey(), g.taxes(), list(wl.get(i).get("taxes")));
        }
        assertThat(note.taxableMinor()).as(where + " taxable").isEqualTo(l(want.get("taxableMinor")));
        assertThat(note.taxMinor()).as(where + " tax").isEqualTo(l(want.get("taxMinor")));
        assertThat(note.roundOffMinor()).as(where + " round-off").isEqualTo(l(want.get("roundOffMinor")));
        assertThat(note.totalMinor()).as(where + " total").isEqualTo(l(want.get("totalMinor")));
    }

    @TestFactory
    Stream<DynamicTest> everyGoldenCaseMatchesTheEngineToTheMinorUnit() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Path dir : packDirs()) {
            Map<String, Object> file = read(dir.resolve("cases.json"));
            Map<String, Object> pack = map(file.get("pack"));
            for (Map<String, Object> c : list(file.get("cases"))) {
                tests.add(DynamicTest.dynamicTest(pack.get("id") + " " + c.get("id") + " " + c.get("title"), () -> runCase(pack, c)));
            }
        }
        assertThat(tests).as("at least one golden pack").isNotEmpty();
        return tests.stream();
    }

    @Test
    void everyPackHasEnoughUniqueCasesAndCarriesTheReviewRecord() throws IOException {
        for (Path dir : packDirs()) {
            Map<String, Object> file = read(dir.resolve("cases.json"));
            Map<String, Object> pack = map(file.get("pack"));
            List<Map<String, Object>> cases = list(file.get("cases"));
            assertThat(cases.size()).as(pack.get("id") + " case count").isGreaterThanOrEqualTo(((Number) pack.get("minCases")).intValue());
            Set<Object> ids = new HashSet<>();
            for (Map<String, Object> c : cases) {
                assertThat(ids.add(c.get("id"))).as("duplicate case id " + c.get("id")).isTrue();
                Map<String, Object> review = map(c.get("review"));
                assertThat(review).as(c.get("id") + " review record").containsKeys("ruleSetVersion", "reviewer", "signedOn");
                assertThat(review.get("ruleSetVersion")).as(c.get("id") + " rule-set version").isEqualTo(pack.get("ruleSetVersion"));
                if (review.get("reviewer") != null) {
                    assertThat(LocalDate.parse((String) review.get("signedOn"))).as(c.get("id") + " signature date").isNotNull();
                } else if (Boolean.getBoolean("golden.requireSigned")) {
                    throw new AssertionError(c.get("id") + " has no CA reviewer: the pack is not signed");
                }
            }
        }
    }

    @Test
    void theWholeGoldenRunTakesUnderThirtySeconds() throws Exception {
        long t0 = System.nanoTime();
        for (Path dir : packDirs()) {
            Map<String, Object> file = read(dir.resolve("cases.json"));
            for (Map<String, Object> c : list(file.get("cases"))) runCase(map(file.get("pack")), c);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void aWrongExpectedFigureFailsTheHarness() throws IOException {
        Map<String, Object> file = read(ROOT.resolve("in/cases.json"));
        Map<String, Object> pack = map(file.get("pack"));
        Map<String, Object> c = map(JSON.readValue(JSON.writeValueAsString(list(file.get("cases")).get(0)), Map.class));
        map(c.get("expected")).put("totalMinor", l(map(c.get("expected")).get("totalMinor")) + 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runCase(pack, c)).isInstanceOf(AssertionError.class);
    }
}
