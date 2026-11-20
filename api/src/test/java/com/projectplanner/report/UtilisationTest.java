package com.projectplanner.report;

import com.projectplanner.report.Utilisation.Person;
import com.projectplanner.report.Utilisation.Result;
import com.projectplanner.report.Utilisation.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UtilisationTest {

    @Test
    void comparesBillableTimeWithCapacity() {
        Result result = Utilisation.of(List.of(
                new Person(2, "Tom", 2400, 1800, 2400),
                new Person(1, "Lena", 1920, 1440, 1920),
                new Person(3, "Maya", 600, 0, 2400)));

        assertThat(result.rows()).extracting(Row::name).containsExactly("Lena", "Maya", "Tom");
        assertThat(result.rows()).extracting(Row::utilisationPct).containsExactly(75.0, 0.0, 75.0);
        // The total compares the summed minutes. It is not the average of the rows.
        assertThat(result.total()).isEqualTo(new Row(null, "Total", 4920, 3240, 6720, 48.2));
    }

    @Test
    void roundsTheShareToOneDecimalAndCanGoOverAHundred() {
        Result result = Utilisation.of(List.of(new Person(1, "Ada", 500, 100, 300), new Person(2, "Bo", 3000, 2700, 2400)));

        assertThat(result.rows()).extracting(Row::utilisationPct).containsExactly(33.3, 112.5);
    }

    @Test
    void hasNoShareWithoutCapacity() {
        Result result = Utilisation.of(List.of(new Person(1, "Ada", 120, 60, 0)));

        assertThat(result.rows().getFirst().utilisationPct()).isNull();
        assertThat(result.total().utilisationPct()).isNull();
        assertThat(Utilisation.of(List.of()).total()).isEqualTo(new Row(null, "Total", 0, 0, 0, null));
    }
}
