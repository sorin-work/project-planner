package com.projectplanner.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void writesRowsWithCrlf() {
        String csv = new CsvWriter().row("a", "b").row(1, 2).toString();
        assertThat(csv).isEqualTo("a,b\r\n1,2\r\n");
    }

    @Test
    void quotesCommasQuotesAndNewlines() {
        assertThat(CsvWriter.escape("Design, round 2")).isEqualTo("\"Design, round 2\"");
        assertThat(CsvWriter.escape("the \"big\" one")).isEqualTo("\"the \"\"big\"\" one\"");
        assertThat(CsvWriter.escape("line one\nline two")).isEqualTo("\"line one\nline two\"");
    }

    @Test
    void neutralisesFormulas() {
        assertThat(CsvWriter.escape("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(CsvWriter.escape("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void leavesNegativeNumbersAlone() {
        assertThat(CsvWriter.escape(new BigDecimal("-2.50"))).isEqualTo("-2.50");
    }

    @Test
    void nullIsEmpty() {
        assertThat(CsvWriter.escape(null)).isEmpty();
    }
}
