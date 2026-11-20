package com.projectplanner.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LinkRuleTest {

    private final WorkCalendar calendar = WorkCalendar.DEFAULT;
    private final LinkRule rule = new LinkRule(calendar);

    // 2026-03-02 is a Monday, so day(4) is a Friday and day(5) a Saturday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    private static ScheduledTask task(LocalDate start, LocalDate due) {
        return new ScheduledTask(1, start, due);
    }

    private LocalDate start(DependencyType type, int lag, ScheduledTask predecessor, ScheduledTask successor) {
        return rule.earliestStart(new Link(1, 2, lag, type), predecessor, successor);
    }

    // The lead across a weekend, written before the rule.

    @Test
    void aLeadOfOneDayOnAFridayFinishIsThatFriday() {
        assertThat(start(DependencyType.FS, -1, task(day(0), day(4)), task(day(0), day(0)))).isEqualTo(day(4));
    }

    @Test
    void aLeadOfOneDayOnAMondayFinishIsThatMonday() {
        assertThat(start(DependencyType.FS, -1, task(day(7), day(7)), task(day(0), day(0)))).isEqualTo(day(7));
    }

    @Test
    void aLeadOfTwoDaysOnAMondayFinishCrossesTheWeekendBackToFriday() {
        assertThat(start(DependencyType.FS, -2, task(day(3), day(7)), task(day(0), day(0)))).isEqualTo(day(4));
    }

    @Test
    void aFinishOnASaturdayIsTheFridayBeforeIt() {
        // Without a lag the successor starts on Monday; a lead of one day is Friday, not Saturday.
        assertThat(start(DependencyType.FS, 0, task(day(0), day(5)), task(day(0), day(0)))).isEqualTo(day(7));
        assertThat(start(DependencyType.FS, -1, task(day(0), day(5)), task(day(0), day(0)))).isEqualTo(day(4));
    }

    @Test
    void finishToStartWithALagCountsWorkingDays() {
        // Due Thursday, next working day Friday, plus two: Tuesday.
        assertThat(start(DependencyType.FS, 2, task(day(0), day(3)), task(day(0), day(0)))).isEqualTo(day(8));
    }

    @Test
    void aLeadLongerThanThePredecessorStartsTheSuccessorBeforeIt() {
        assertThat(start(DependencyType.FS, -4, task(day(9), day(10)), task(day(0), day(0)))).isEqualTo(day(7));
    }

    @Test
    void startToStartCountsFromTheStartAndAWeekendStartIsTheMondayAfter() {
        assertThat(start(DependencyType.SS, 0, task(day(2), day(9)), task(day(0), day(1)))).isEqualTo(day(2));
        assertThat(start(DependencyType.SS, 2, task(day(2), day(9)), task(day(0), day(1)))).isEqualTo(day(4));
        assertThat(start(DependencyType.SS, 0, task(day(5), day(9)), task(day(0), day(1)))).isEqualTo(day(7));
        assertThat(start(DependencyType.SS, -1, task(day(7), day(9)), task(day(0), day(1)))).isEqualTo(day(4));
    }

    @Test
    void finishToFinishMovesTheSuccessorSoThatItsFinishFitsAndKeepsItsLength() {
        // The predecessor is due on Wednesday the 11th. A three-day successor may end then at the earliest: Monday to Wednesday.
        assertThat(start(DependencyType.FF, 0, task(day(7), day(9)), task(day(0), day(2)))).isEqualTo(day(7));
        // One day later: Tuesday to Thursday.
        assertThat(start(DependencyType.FF, 1, task(day(7), day(9)), task(day(0), day(2)))).isEqualTo(day(8));
        // A five-day successor that has to end on Monday starts on the Tuesday before.
        assertThat(start(DependencyType.FF, 0, task(day(0), day(7)), task(day(0), day(4)))).isEqualTo(day(1));
    }

    @Test
    void startToFinishHoldsTheFinishAgainstTheStart() {
        // The predecessor starts on Wednesday. Without a lag the successor ends the working day before, Tuesday,
        // the way finish-to-start starts the day after: a two-day successor runs Monday to Tuesday.
        assertThat(start(DependencyType.SF, 0, task(day(2), day(9)), task(day(0), day(1)))).isEqualTo(day(0));
        // A lag of one lets it end on the predecessor's first day.
        assertThat(start(DependencyType.SF, 1, task(day(2), day(9)), task(day(0), day(1)))).isEqualTo(day(1));
        // Before a Monday start the day before is the Friday: a one-day successor ends there.
        assertThat(start(DependencyType.SF, 0, task(day(7), day(9)), task(day(0), day(0)))).isEqualTo(day(4));
        // A Saturday start counts as the Monday after it.
        assertThat(start(DependencyType.SF, 0, task(day(5), day(9)), task(day(0), day(0)))).isEqualTo(day(4));
    }

    @Test
    void aTaskOnADayOffOnlyIsAOneDayTaskOnTheNextWorkingDay() {
        // A Saturday milestone is a Monday milestone: finish-to-start plus five lands on Tuesday a week later.
        assertThat(start(DependencyType.FS, 5, task(day(5), day(5)), task(day(0), day(0)))).isEqualTo(day(15));
        assertThat(start(DependencyType.FS, 5, task(null, day(5)), task(day(0), day(0)))).isEqualTo(day(15));
        // A weekend-only task, Saturday to Sunday, as well.
        assertThat(rule.normalise(task(day(5), day(6)))).isEqualTo(task(day(7), day(7)));
        // A task with a working day in it is left as it is.
        assertThat(rule.normalise(task(day(4), day(6)))).isEqualTo(task(day(4), day(6)));
    }

    @Test
    void saysNothingWithoutTheDateItCountsFrom() {
        assertThat(start(DependencyType.FS, 0, task(day(0), null), task(day(0), day(1)))).isNull();
        assertThat(start(DependencyType.FF, 0, task(day(0), null), task(day(0), day(1)))).isNull();
        assertThat(start(DependencyType.SS, 0, task(null, day(3)), task(day(0), day(1)))).isNull();
        assertThat(start(DependencyType.SF, 0, task(null, day(3)), task(day(0), day(1)))).isNull();
        assertThat(rule.earliestStart(new Link(1, 2), null, task(day(0), day(1)))).isNull();
    }

    @Test
    void theOffsetsOfTheCriticalPathSayTheSameAsTheDates() {
        // On working days only, an offset is a working day counted from the origin. Every type, every lag
        // from a lead of ten days to a lag of ten, and lengths up to fifteen days.
        Random random = new Random(17);
        LocalDate origin = day(0);
        for (int i = 0; i < 5000; i++) {
            DependencyType type = DependencyType.values()[random.nextInt(4)];
            int lag = random.nextInt(21) - 10;
            int predecessorStart = 15 + random.nextInt(20);
            int predecessorLength = 1 + random.nextInt(15);
            int successorLength = 1 + random.nextInt(15);
            ScheduledTask predecessor = task(calendar.addWorkingDays(origin, predecessorStart),
                    calendar.addWorkingDays(origin, predecessorStart + predecessorLength - 1));
            if (random.nextInt(4) == 0) {
                // A task on days off only: the weekend before the working day at predecessorStart, which it
                // stands for as a one-day task, so it counts as that day in the offsets too.
                LocalDate monday = calendar.addWorkingDays(origin, predecessorStart);
                while (monday.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
                    predecessorStart++;
                    monday = calendar.addWorkingDays(origin, predecessorStart);
                }
                predecessorLength = 1;
                LocalDate saturday = monday.minusDays(2);
                predecessor = task(saturday, random.nextBoolean() ? saturday : saturday.plusDays(1));
            }
            ScheduledTask successor = task(origin, calendar.addWorkingDays(origin, successorLength - 1));
            Link link = new Link(1, 2, lag, type);

            int offset = LinkRule.earliestStartOffset(link, predecessorStart, predecessorStart + predecessorLength - 1, successorLength);
            assertThat(rule.earliestStart(link, predecessor, successor)).as("%s %d", type, lag)
                    .isEqualTo(calendar.addWorkingDays(origin, offset));

            // Backwards: the latest finish the predecessor may have is the one whose earliest start for the
            // successor is exactly the successor's start.
            int successorStart = 40;
            int latest = LinkRule.latestFinishOffset(link, successorStart, successorStart + successorLength - 1, predecessorLength);
            int asLate = latest - predecessorLength + 1;
            assertThat(LinkRule.earliestStartOffset(link, asLate, latest, successorLength)).as("%s %d back", type, lag).isEqualTo(successorStart);
        }
    }
}
