package com.nabd.hms.billing.tax;

import com.nabd.hms.pharmacy.api.PharmacyTaxInputs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-021: the pharmacy golden packs (golden/phl-{in,sa,ae,qa}, at least 20 cases per market). {@link GoldenPackTest} runs every
 * pack's carts through the tax engine against the independent oracle's figures; this test proves the other half: the inputs pharmacy
 * supplies (zero-rated flag, tax-inclusive price) fold into exactly the cart line the pack then taxes. Pharmacy computes no tax.
 */
class PharmacyGoldenFoldTest {

    private static final Path ROOT = Path.of("src/test/resources/golden");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String dir) throws IOException {
        return JSON.readValue(Files.readString(ROOT.resolve(dir).resolve("cases.json")), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyMarketHasTwentyCasesAndEachPharmacyLineFoldsToThePackLine() throws IOException {
        for (String dir : List.of("phl-in", "phl-sa", "phl-ae", "phl-qa")) {
            Map<String, Object> pack = read(dir);
            List<Map<String, Object>> cases = (List<Map<String, Object>>) pack.get("cases");
            assertThat(cases.size()).as(dir + " case count").isGreaterThanOrEqualTo(20);
            int folded = 0;
            for (Map<String, Object> c : cases) {
                for (Map<String, Object> line : (List<Map<String, Object>>) ((Map<String, Object>) c.get("cart")).get("lines")) {
                    Map<String, Object> ph = (Map<String, Object>) line.get("pharmacy");
                    if (ph == null) continue; // a non-pharmacy line (consultation) rides along in a mixed-cart case
                    Map<String, Object> base = (Map<String, Object>) ph.get("base");
                    var in = new PharmacyTaxInputs.Inputs((Boolean) ph.get("priceInclusive"), (Boolean) ph.get("zeroRated"));
                    var got = PharmacyTaxLines.engineLine((String) line.get("key"), ((Number) line.get("grossMinor")).longValue(),
                            (String) base.get("category"), new BigDecimal((String) base.get("ratePercent")), (Boolean) base.get("inclusive"),
                            (Boolean) base.get("interState"), in);
                    String where = c.get("id") + "/" + line.get("key");
                    assertThat(got.category().name()).as(where + " category").isEqualTo(line.get("category"));
                    assertThat(got.ratePercent()).as(where + " rate").isEqualByComparingTo(new BigDecimal((String) line.get("ratePercent")));
                    assertThat(got.inclusive()).as(where + " inclusive").isEqualTo(line.get("inclusive"));
                    assertThat(got.interState()).as(where + " interState").isEqualTo(line.get("interState"));
                    folded++;
                }
            }
            assertThat(folded).as(dir + " folded lines").isGreaterThanOrEqualTo(20);
        }
    }

    @Test
    void indiaMrpExampleGivesTaxable100AndSixPlusSixOn112() throws IOException {
        var line = PharmacyTaxLines.engineLine("med", 11200, "TAXABLE", new BigDecimal("12"), false, false, new PharmacyTaxInputs.Inputs(true, false));
        var r = new RuleBasedTaxEngine(new IndiaGst()).compute(new TaxEngine.Cart(2, false, false, 0, List.of(line)));
        var l = r.lines().get(0);
        assertThat(l.taxableMinor()).isEqualTo(10000);
        assertThat(l.taxes()).extracting(TaxEngine.Component::type).containsExactly("CGST", "SGST");
        assertThat(l.taxes()).extracting(TaxEngine.Component::amountMinor).containsExactly(600L, 600L);
        assertThat(r.totalMinor()).isEqualTo(11200);
    }

    @Test
    void noPharmacyProfileLeavesTheHeadsClassUntouched() {
        var line = PharmacyTaxLines.engineLine("x", 1000, "TAXABLE", new BigDecimal("18"), true, true, null);
        assertThat(line.inclusive()).isTrue();
        assertThat(line.interState()).isTrue();
        assertThat(line.category()).isEqualTo(TaxEngine.Category.TAXABLE);
    }
}
