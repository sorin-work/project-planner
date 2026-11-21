package com.projectplanner.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceMathTest {

    @Test
    void amountsAreWholeCents() {
        assertThat(InvoiceMath.cents(new BigDecimal("11.67"))).isEqualTo(1167);
        assertThat(InvoiceMath.cents(new BigDecimal("11.665"))).isEqualTo(1167);
        assertThat(InvoiceMath.cents(null)).isZero();
        assertThat(InvoiceMath.amount(1167)).isEqualByComparingTo("11.67");
        assertThat(InvoiceMath.amount(-5)).isEqualByComparingTo("-0.05");
    }

    @Test
    void aLineIsPricedOnceFromItsMinutes() {
        // Three 20 minute entries at 100.00 an hour: 100.00, where pricing each would make 99.99.
        assertThat(InvoiceMath.lineAmount(60, 10000)).isEqualTo(10000);
        assertThat(InvoiceMath.lineAmount(14, 10000)).isEqualTo(2333);
        assertThat(InvoiceMath.lineAmount(7, 10000)).isEqualTo(1167);
        assertThat(InvoiceMath.lineAmount(90, 9550)).isEqualTo(14325);
    }

    @Test
    void taxIsRoundedHalfUpToTheCent() {
        assertThat(InvoiceMath.tax(10000, 2000)).isEqualTo(2000);
        assertThat(InvoiceMath.tax(1167, 1900)).isEqualTo(222); // 221.73
        assertThat(InvoiceMath.tax(250, 1000)).isEqualTo(25);
        assertThat(InvoiceMath.tax(5, 1000)).isEqualTo(1); // 0.5 goes up
        assertThat(InvoiceMath.tax(0, 2000)).isZero();
    }

    @Test
    void aTaxRateHasTwoDecimalsAtMost() {
        assertThat(InvoiceMath.basisPoints(new BigDecimal("20"))).isEqualTo(2000);
        assertThat(InvoiceMath.basisPoints(new BigDecimal("7.75"))).isEqualTo(775);
        assertThatThrownBy(() -> InvoiceMath.basisPoints(new BigDecimal("7.755"))).isInstanceOf(ArithmeticException.class);
    }
}
