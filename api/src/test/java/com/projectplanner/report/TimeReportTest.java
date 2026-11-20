package com.projectplanner.report;

import com.projectplanner.report.TimeReport.Bucket;
import com.projectplanner.report.TimeReport.Group;
import com.projectplanner.report.TimeReport.Result;
import com.projectplanner.report.TimeReport.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TimeReportTest {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Function<Bucket, Group> BY_PROJECT = b -> new Group(String.valueOf(b.projectId()), "Project " + b.projectId());
    private static final Function<Bucket, Group> BY_PERSON = b -> new Group(String.valueOf(b.userId()), "Person " + b.userId());

    private static Bucket bucket(long projectId, long userId, boolean billable, int minutes, long count, BigDecimal rate) {
        return new Bucket(projectId, userId, null, billable, minutes, count, rate);
    }

    @Test
    void addsUpMinutesAndBillableMinutesPerRow() {
        Result result = TimeReport.group(List.of(
                bucket(1, 7, true, 90, 2, HUNDRED),
                bucket(1, 8, false, 30, 1, HUNDRED),
                bucket(2, 7, true, 60, 4, HUNDRED)), BY_PROJECT);

        assertThat(result.rows()).extracting(Row::key, Row::label, Row::minutes, Row::billableMinutes)
                .containsExactly(
                        tuple("2", "Project 2", 240L, 240L),
                        tuple("1", "Project 1", 210L, 180L));
        assertThat(result.total().minutes()).isEqualTo(450);
        assertThat(result.total().billableMinutes()).isEqualTo(420);
    }

    @Test
    void pricesEveryEntryOnItsOwnAndRoundsItToACent() {
        // Twenty minutes at 100 an hour are 33.33. Three such entries are 99.99, an hour in one entry is 100.00.
        Result three = TimeReport.group(List.of(bucket(1, 7, true, 20, 3, HUNDRED)), BY_PROJECT);
        Result one = TimeReport.group(List.of(bucket(1, 7, true, 60, 1, HUNDRED)), BY_PROJECT);
        assertThat(three.total().billableAmount()).isEqualByComparingTo("99.99");
        assertThat(one.total().billableAmount()).isEqualByComparingTo("100.00");
        assertThat(three.total().minutes()).isEqualTo(one.total().minutes());

        // Half a cent goes up: one minute at 30 an hour is 0.50, at 31 it is 0.5166 and so 0.52.
        assertThat(TimeReport.centsPerEntry(bucket(1, 7, true, 1, 1, BigDecimal.valueOf(30)))).isEqualTo(50);
        assertThat(TimeReport.centsPerEntry(bucket(1, 7, true, 1, 1, BigDecimal.valueOf(31)))).isEqualTo(52);
        assertThat(TimeReport.centsPerEntry(bucket(1, 7, true, 1, 1, new BigDecimal("0.30")))).isEqualTo(1);
    }

    @Test
    void theTotalIsTheSumOfTheRowsToTheCent() {
        Result result = TimeReport.group(List.of(
                bucket(1, 7, true, 20, 3, HUNDRED),
                bucket(1, 8, true, 7, 11, new BigDecimal("83.50")),
                bucket(2, 7, true, 50, 5, new BigDecimal("71.25")),
                bucket(2, 8, true, 1, 9, new BigDecimal("95")),
                bucket(3, 8, false, 45, 2, HUNDRED)), BY_PROJECT);

        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().stream().mapToLong(Row::billableCents).sum()).isEqualTo(result.total().billableCents());
        assertThat(result.rows().stream().mapToLong(Row::minutes).sum()).isEqualTo(result.total().minutes());
        assertThat(result.rows().stream().mapToLong(Row::billableMinutes).sum()).isEqualTo(result.total().billableMinutes());
        // The same entries grouped another way cost the same.
        assertThat(TimeReport.group(List.of(
                bucket(1, 7, true, 20, 3, HUNDRED),
                bucket(1, 8, true, 7, 11, new BigDecimal("83.50")),
                bucket(2, 7, true, 50, 5, new BigDecimal("71.25")),
                bucket(2, 8, true, 1, 9, new BigDecimal("95")),
                bucket(3, 8, false, 45, 2, HUNDRED)), BY_PERSON).total()).isEqualTo(result.total());
    }

    @Test
    void timeThatIsNotBillableOrHasNoRateCostsNothing() {
        Result result = TimeReport.group(List.of(
                bucket(1, 7, false, 60, 2, HUNDRED),
                bucket(1, 7, true, 60, 2, null)), BY_PROJECT);

        assertThat(result.total().minutes()).isEqualTo(240);
        assertThat(result.total().billableMinutes()).isEqualTo(120);
        assertThat(result.total().billableAmount()).isEqualByComparingTo("0.00");
        assertThat(result.total().billableAmount().scale()).isEqualTo(2);
    }

    @Test
    void sortsByTimeAndThenByName() {
        Result result = TimeReport.group(List.of(
                bucket(3, 7, true, 60, 1, HUNDRED),
                bucket(1, 7, true, 60, 1, HUNDRED),
                bucket(2, 7, true, 90, 1, HUNDRED)), BY_PROJECT);

        assertThat(result.rows()).extracting(Row::label).containsExactly("Project 2", "Project 1", "Project 3");
        assertThat(TimeReport.group(List.of(), BY_PROJECT).rows()).isEmpty();
        assertThat(TimeReport.group(List.of(), BY_PROJECT).total()).isEqualTo(new Row("total", "Total", 0, 0, 0));
    }
}
