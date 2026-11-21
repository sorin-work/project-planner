package com.projectplanner.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The arithmetic of an invoice, in cents. */
public final class InvoiceMath {

    private InvoiceMath() {
    }

    /** An amount of money as cents, rounded half up. Null counts as nothing. */
    public static long cents(BigDecimal amount) {
        return amount == null ? 0 : amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** Cents as an amount with two decimals, for the CSV. */
    public static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /** The tax on {@code subtotalCents} at {@code rateBp} basis points, rounded half up to the cent (away from zero for a credit). */
    public static long tax(long subtotalCents, int rateBp) {
        return BigDecimal.valueOf(subtotalCents).multiply(BigDecimal.valueOf(rateBp))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** A tax rate in percent, two decimals at most, as basis points. */
    public static int basisPoints(BigDecimal percent) {
        return percent.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact();
    }
}
