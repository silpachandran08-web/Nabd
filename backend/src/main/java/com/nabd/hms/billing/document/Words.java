package com.nabd.hms.billing.document;

import java.math.BigDecimal;

/** FIN-012: amount in words, Indian grouping (lakh, crore) as the GST invoice format expects. */
final class Words {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
            "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private Words() {
    }

    static String indianRupees(BigDecimal amount) {
        long paise = amount.abs().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        long rupees = paise / 100;
        int ps = (int) (paise % 100);
        String r = (amount.signum() < 0 ? "Minus " : "") + "Rupees " + (rupees == 0 ? "Zero" : below(rupees));
        return ps == 0 ? r + " Only" : r + " and " + below(ps) + " Paise Only";
    }

    private static String below(long n) {
        StringBuilder b = new StringBuilder();
        long[] unit = {10_000_000L, 100_000L, 1000L, 100L};
        String[] name = {"Crore", "Lakh", "Thousand", "Hundred"};
        for (int i = 0; i < unit.length; i++) {
            if (n >= unit[i]) {
                b.append(below(n / unit[i])).append(' ').append(name[i]).append(' ');
                n %= unit[i];
            }
        }
        if (n >= 20) { b.append(TENS[(int) n / 10]).append(' '); n %= 10; }
        if (n > 0) b.append(ONES[(int) n]);
        return b.toString().trim();
    }
}
