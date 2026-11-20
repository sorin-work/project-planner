package com.projectplanner.time;

import com.projectplanner.time.MissingTimesheets.Missing;
import com.projectplanner.time.MissingTimesheets.Person;
import com.projectplanner.time.MissingTimesheets.Skip;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MissingTimesheetsTest {

    // 2026-03-02 is a Monday.
    private static final LocalDate WEEK = LocalDate.of(2026, 3, 2);

    private static Person person(long id, String name, LocalDate joined) {
        return new Person(id, name, joined, BigDecimal.valueOf(40));
    }

    private static Person person(long id, String name) {
        return person(id, name, WEEK.minusMonths(6));
    }

    @Test
    void aWeekIsOverOnceItsSundayHasPassed() {
        assertThat(MissingTimesheets.latestEndedWeek(WEEK.plusDays(6))).isEqualTo(WEEK.minusWeeks(1));
        assertThat(MissingTimesheets.latestEndedWeek(WEEK.plusDays(7))).isEqualTo(WEEK);
        assertThat(MissingTimesheets.latestEndedWeek(WEEK.plusDays(10))).isEqualTo(WEEK);
    }

    @Test
    void listsDraftsAndWeeksThatWereSentBack() {
        List<Missing> missing = MissingTimesheets.find(WEEK,
                List.of(person(1, "Zoe"), person(2, "Ada"), person(3, "Ben"), person(4, "Cal"), person(5, "Dee")),
                Map.of(2L, TimesheetStatus.REJECTED, 3L, TimesheetStatus.SUBMITTED, 4L, TimesheetStatus.APPROVED,
                        5L, TimesheetStatus.DRAFT),
                Map.of(1L, 90, 2L, 2400, 3L, 2400), Set.of(2L));

        assertThat(missing).extracting(Missing::userName).containsExactly("Ada", "Dee", "Zoe");
        Missing ada = missing.getFirst();
        assertThat(ada.status()).isEqualTo(TimesheetStatus.REJECTED);
        assertThat(ada.totalMinutes()).isEqualTo(2400);
        assertThat(ada.weeklyCapacityHours()).isEqualByComparingTo("40");
        assertThat(ada.remindedToday()).isTrue();
        // Never submitted: no status, no time and no reminder.
        Missing zoe = missing.getLast();
        assertThat(zoe.status()).isEqualTo(TimesheetStatus.DRAFT);
        assertThat(zoe.totalMinutes()).isEqualTo(90);
        assertThat(zoe.remindedToday()).isFalse();
        assertThat(missing.get(1).totalMinutes()).isZero();
    }

    @Test
    void leavesOutPeopleWhoJoinedAfterTheWeek() {
        List<Missing> missing = MissingTimesheets.find(WEEK,
                List.of(person(1, "Early"), person(2, "Sunday", WEEK.plusDays(6)), person(3, "Monday", WEEK.plusDays(7))),
                Map.of(), Map.of(), Set.of());

        assertThat(missing).extracting(Missing::userName).containsExactly("Early", "Sunday");
    }

    @Test
    void saysWhySomebodyGetsNoReminder() {
        Missing late = new Missing(7, "Ada", TimesheetStatus.DRAFT, 120, BigDecimal.valueOf(40), false);
        Missing reminded = new Missing(8, "Ben", TimesheetStatus.REJECTED, 120, BigDecimal.valueOf(40), true);

        assertThat(MissingTimesheets.skipReason(late, 1)).isEmpty();
        assertThat(MissingTimesheets.skipReason(null, 1)).contains(Skip.NOT_MISSING);
        assertThat(MissingTimesheets.skipReason(late, 7)).contains(Skip.YOURSELF);
        assertThat(MissingTimesheets.skipReason(reminded, 1)).contains(Skip.ALREADY_REMINDED);
    }

    @Test
    void limitsRemindersPerWeekAndPerDay() {
        List<LocalDate> fourWeeks = List.of(WEEK.minusWeeks(1), WEEK.minusWeeks(2), WEEK.minusWeeks(3), WEEK.minusWeeks(4));
        List<LocalDate> fiveWeeks = List.of(WEEK.minusWeeks(1), WEEK.minusWeeks(2), WEEK.minusWeeks(3), WEEK.minusWeeks(4),
                WEEK.minusWeeks(5));

        assertThat(MissingTimesheets.reminderLimit(WEEK, List.of())).isEmpty();
        assertThat(MissingTimesheets.reminderLimit(WEEK, fourWeeks)).isEmpty();
        assertThat(MissingTimesheets.reminderLimit(WEEK.minusWeeks(2), fourWeeks)).contains(Skip.ALREADY_REMINDED);
        assertThat(MissingTimesheets.reminderLimit(WEEK, fiveWeeks)).contains(Skip.DAILY_LIMIT);
        assertThat(MissingTimesheets.reminderLimit(WEEK.minusWeeks(5), fiveWeeks)).contains(Skip.ALREADY_REMINDED);
    }
}
