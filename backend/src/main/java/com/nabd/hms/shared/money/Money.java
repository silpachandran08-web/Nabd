package com.nabd.hms.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** FIN-006: an amount in the currency's minor unit (paise, halalas, fils); the exponent comes from the ISO currency, never a literal 2. */
public record Money(long amountMinor, Currency currency) {

    /** Rounds half away from zero, the same as the database's to_minor(). */
    public static Money of(BigDecimal amount, String currencyCode) {
        Currency c = Currency.getInstance(currencyCode);
        return new Money(amount.movePointRight(c.getDefaultFractionDigits()).setScale(0, RoundingMode.HALF_UP).longValueExact(), c);
    }

    public BigDecimal amount() {
        return BigDecimal.valueOf(amountMinor, currency.getDefaultFractionDigits());
    }
}
