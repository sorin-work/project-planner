package com.projectplanner.common;

import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void writesRowsWithCrlf() {
        String csv = new CsvWriter().row("a", "b").row(1, 2).toString();
        assertThat(csv).isEqualTo("a,b\r\n1,2\r\n");
    }

    @Test
    void nullIsEmpty() {
        assertThat(CsvWriter.escape(null)).isEmpty();
    }
}
