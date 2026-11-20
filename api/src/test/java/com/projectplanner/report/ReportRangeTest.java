package com.projectplanner.report;

import com.projectplanner.common.ApiException;
import com.projectplanner.report.ReportRange.Preset;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRangeTest {

    // 2026-03-11 is a Wednesday.
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 11);

    private static ReportRange range(String from, String to) {
        return new ReportRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void weeksRunFromMondayToSunday() {
        assertThat(ReportRange.of(Preset.THIS_WEEK, TODAY)).isEqualTo(range("2026-03-09", "2026-03-15"));
        assertThat(ReportRange.of(Preset.LAST_WEEK, TODAY)).isEqualTo(range("2026-03-02", "2026-03-08"));
        // On a Monday and on a Sunday the week is the one that day is in.
        assertThat(ReportRange.of(Preset.THIS_WEEK, LocalDate.of(2026, 3, 9))).isEqualTo(range("2026-03-09", "2026-03-15"));
        assertThat(ReportRange.of(Preset.THIS_WEEK, LocalDate.of(2026, 3, 15))).isEqualTo(range("2026-03-09", "2026-03-15"));
    }

    @Test
    void monthsRunFromTheirFirstToTheirLastDay() {
        assertThat(ReportRange.of(Preset.THIS_MONTH, TODAY)).isEqualTo(range("2026-03-01", "2026-03-31"));
        assertThat(ReportRange.of(Preset.LAST_MONTH, TODAY)).isEqualTo(range("2026-02-01", "2026-02-28"));
        assertThat(ReportRange.of(Preset.THIS_MONTH, LocalDate.of(2028, 2, 29))).isEqualTo(range("2028-02-01", "2028-02-29"));
    }

    @Test
    void lastWeekAndLastMonthCrossTheYear() {
        LocalDate newYear = LocalDate.of(2026, 1, 1);
        assertThat(ReportRange.of(Preset.LAST_MONTH, newYear)).isEqualTo(range("2025-12-01", "2025-12-31"));
        assertThat(ReportRange.of(Preset.THIS_WEEK, newYear)).isEqualTo(range("2025-12-29", "2026-01-04"));
        assertThat(ReportRange.of(Preset.LAST_WEEK, newYear)).isEqualTo(range("2025-12-22", "2025-12-28"));
    }

    @Test
    void aPresetWinsOverDatesAndIsReadLeniently() {
        LocalDate from = LocalDate.of(2020, 1, 1);
        assertThat(ReportRange.resolve("last_week", from, from, TODAY)).isEqualTo(range("2026-03-02", "2026-03-08"));
        assertThat(ReportRange.resolve(" This-Month ", null, null, TODAY)).isEqualTo(range("2026-03-01", "2026-03-31"));
        assertThatThrownBy(() -> ReportRange.resolve("yesterday", null, null, TODAY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_preset"));
    }

    @Test
    void datesAreBothIncludedAndCoverAtMostTwoYears() {
        assertThat(ReportRange.resolve(null, TODAY, TODAY, TODAY)).isEqualTo(new ReportRange(TODAY, TODAY));
        assertThat(ReportRange.resolve("", TODAY.minusYears(2), TODAY, TODAY).from()).isEqualTo(TODAY.minusYears(2));
        for (LocalDate[] bad : new LocalDate[][] {{null, TODAY}, {TODAY, null}, {TODAY, TODAY.minusDays(1)},
                {TODAY.minusYears(2).minusDays(1), TODAY}}) {
            assertThatThrownBy(() -> ReportRange.resolve(null, bad[0], bad[1], TODAY))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_range"));
        }
    }
}
