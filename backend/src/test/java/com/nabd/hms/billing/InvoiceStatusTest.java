package com.nabd.hms.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-001: status derivation table. */
class InvoiceStatusTest {

    private static String d(String current, String total, String paid) {
        return InvoiceStatus.derive(current, new BigDecimal(total), new BigDecimal(paid));
    }

    @Test
    void statusFollowsPaidVersusTotal() {
        assertThat(d("unpaid", "500", "0")).isEqualTo("unpaid");
        assertThat(d("unpaid", "500", "0.01")).isEqualTo("partial");
        assertThat(d("partial", "500", "499.99")).isEqualTo("partial");
        assertThat(d("partial", "500", "500")).isEqualTo("paid");
        assertThat(d("paid", "1700", "500")).isEqualTo("partial"); // total grew after an interim payment
        assertThat(d("paid", "500", "0")).isEqualTo("unpaid");
        assertThat(d("unpaid", "0", "0")).isEqualTo("unpaid");     // nothing to collect is not "paid"
    }

    @Test
    void closedStatesNeverMove() {
        for (String s : new String[]{"void", "cancelled", "refunded"}) {
            assertThat(d(s, "500", "500")).isEqualTo(s);
            assertThat(d(s, "500", "0")).isEqualTo(s);
        }
    }
}
