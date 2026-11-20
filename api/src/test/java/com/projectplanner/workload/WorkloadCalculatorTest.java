package com.projectplanner.workload;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.workload.WorkloadCalculator.Assignment;
import com.projectplanner.workload.WorkloadCalculator.MemberLoad;
import com.projectplanner.workload.WorkloadCalculator.Person;
import com.projectplanner.workload.WorkloadCalculator.WeekLoad;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadCalculatorTest {

    private final WorkloadCalculator calculator = new WorkloadCalculator(WorkCalendar.DEFAULT);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    private final Person ana = new Person(1, 40);
    private final Person ben = new Person(2, 20);

    @Test
    void spreadsEstimateEvenlyOverWorkingDays() {
        // 20 hours over two weeks (10 working days): 10 hours per week.
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(100, 1, 20, day(0), day(11), false)), day(0), 3);
        MemberLoad load = loads.getFirst();
        assertThat(load.weeks()).extracting(WorkloadCalculator.WeekLoad::allocatedHours).containsExactly(10.0, 10.0, 0.0);
        assertThat(load.weeks().getFirst().taskIds()).containsExactly(100L);
        assertThat(load.weeks().get(2).taskIds()).isEmpty();
    }

    @Test
    void flagsOverallocatedWeeks() {
        List<MemberLoad> loads = calculator.calculate(List.of(ben), List.of(
                new Assignment(1, 2, 15, day(0), day(4), false),
                new Assignment(2, 2, 10, day(2), day(4), false)), day(0), 1);
        var week = loads.getFirst().weeks().getFirst();
        assertThat(week.allocatedHours()).isEqualTo(25.0);
        assertThat(week.overallocated()).isTrue();
        assertThat(week.capacityHours()).isEqualTo(20.0);
    }

    @Test
    void onlyCountsTheVisibleWindow() {
        // A 4-week task starting two weeks before the window.
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(1, 1, 80, day(-14), day(11), false)), day(0), 1);
        assertThat(loads.getFirst().weeks().getFirst().allocatedHours()).isEqualTo(20.0);
    }

    @Test
    void finishedTasksDoNotCount() {
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(1, 1, 40, day(0), day(4), true)), day(0), 1);
        assertThat(loads.getFirst().weeks().getFirst().allocatedHours()).isZero();
    }

    @Test
    void tasksWithoutDatesAreUnscheduled() {
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(1, 1, 12, null, null, false)), day(0), 2);
        assertThat(loads.getFirst().unscheduledHours()).isEqualTo(12.0);
        assertThat(loads.getFirst().weeks()).allMatch(w -> w.allocatedHours() == 0);
    }

    @Test
    void singleDateTasksAreBookedOnThatDay() {
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(1, 1, 6, null, day(8), false)), day(0), 2);
        assertThat(loads.getFirst().weeks()).extracting(WorkloadCalculator.WeekLoad::allocatedHours).containsExactly(0.0, 6.0);
    }

    @Test
    void weekendOnlyTasksMoveToMonday() {
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(1, 1, 4, day(5), day(6), false)), day(0), 2);
        assertThat(loads.getFirst().weeks()).extracting(WorkloadCalculator.WeekLoad::allocatedHours).containsExactly(0.0, 4.0);
    }

    @Test
    void windowStartsOnMonday() {
        List<MemberLoad> loads = calculator.calculate(List.of(ana), List.of(), day(3), 1);
        assertThat(loads.getFirst().weeks().getFirst().weekStart()).isEqualTo(day(0));
    }

    @Test
    void ignoresPeopleOutsideTheTeam() {
        List<MemberLoad> loads = calculator.calculate(List.of(ana),
                List.of(new Assignment(1, 99, 10, day(0), day(0), false)), day(0), 1);
        assertThat(loads).hasSize(1);
        assertThat(loads.getFirst().weeks().getFirst().allocatedHours()).isZero();
    }

    @Test
    void capacityIsTheWeeklyHoursSpreadOverTheWorkingDays() {
        Person lena = new Person(3, 32);
        // A whole week, Monday to Sunday, is exactly what the weeks of the grid show.
        assertThat(calculator.capacityMinutes(ana, day(0), day(6))).isEqualTo(40 * 60);
        assertThat(calculator.capacityMinutes(lena, day(0), day(6))).isEqualTo(32 * 60);
        assertThat(calculator.calculate(List.of(lena), List.of(), day(0), 1).getFirst().weeks().getFirst().capacityHours()).isEqualTo(32.0);
        // Monday to Wednesday are three fifths of it, and a weekend is nothing.
        assertThat(calculator.capacityMinutes(ana, day(0), day(2))).isEqualTo(24 * 60);
        assertThat(calculator.capacityMinutes(lena, day(0), day(2))).isEqualTo(1152);
        assertThat(calculator.capacityMinutes(ana, day(5), day(6))).isZero();
        // March 2026 has 22 working days.
        assertThat(calculator.capacityMinutes(ana, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))).isEqualTo(22 * 8 * 60);
        assertThat(calculator.capacityMinutes(ana, day(3), day(1))).isZero();
    }

    @Test
    void holidaysAndDaysOffLowerTheCapacityOfTheirWeek() {
        // Wednesday of the first week is a holiday. Ana is away on Thursday and Friday of the second week.
        WorkloadCalculator withHoliday = new WorkloadCalculator(new WorkCalendar(List.of(day(2))));
        Person away = new Person(1, 40, java.util.Set.of(day(10), day(11), day(12)));
        List<WeekLoad> weeks = withHoliday.calculate(List.of(away),
                List.of(new Assignment(1, 1, 8, day(0), day(4), false)), day(0), 3).getFirst().weeks();

        assertThat(weeks.get(0).capacityHours()).isEqualTo(32.0);
        assertThat(weeks.get(0).holidays()).isEqualTo(1);
        assertThat(weeks.get(0).daysOff()).isZero();
        // Saturday, the third day off, was never a working day.
        assertThat(weeks.get(1).capacityHours()).isEqualTo(24.0);
        assertThat(weeks.get(1).daysOff()).isEqualTo(2);
        assertThat(weeks.get(2).capacityHours()).isEqualTo(40.0);
        // The task is spread over its four working days, all of it in the first week.
        assertThat(weeks.get(0).allocatedHours()).isEqualTo(8.0);
        assertThat(withHoliday.capacityMinutes(away, day(0), day(13))).isEqualTo(56 * 60);
    }

    @Test
    void aTaskIsSpreadOverItsOwnWorkingDaysNotAroundADayOff() {
        // Two days of a five-day task are days off for Ana: the task still books a fifth a day.
        Person away = new Person(1, 40, java.util.Set.of(day(0), day(1)));
        List<WeekLoad> weeks = calculator.calculate(List.of(away),
                List.of(new Assignment(1, 1, 30, day(0), day(4), false)), day(0), 1).getFirst().weeks();
        assertThat(weeks.getFirst().allocatedHours()).isEqualTo(30.0);
        assertThat(weeks.getFirst().capacityHours()).isEqualTo(24.0);
        assertThat(weeks.getFirst().overallocated()).isTrue();
    }
}
