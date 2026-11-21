package com.projectplanner.time;

import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.org.Membership;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.TestClock;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AutoRemindersTest {

    /** Monday 30 March 2026 in Berlin, summer time since the day before. Last week started on the 23rd. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 30);

    @Inject
    AutoReminders reminders;

    @AfterEach
    void resetClock() {
        TestClock.reset();
    }

    private record Team(Account owner, Account late, Account away) {
    }

    /** Late logged time last week and did not submit it; away logged none. */
    private static Team team(boolean on) {
        Account owner = Api.signup("Ada Auto");
        Account late = Api.signup("Leo Late");
        Account away = Api.signup("Ava Away");
        Api.addMember(owner, late, "MEMBER");
        Api.addMember(owner, away, "MEMBER");
        owner.as().body(Map.of("timezone", "Europe/Berlin", "autoReminders", on)).patch("/api/orgs/" + owner.orgId())
                .then().statusCode(200).body("autoReminders", org.hamcrest.Matchers.equalTo(on));
        QuarkusTransaction.requiringNew().run(() -> Membership.update("createdAt = ?1 where organisationId = ?2",
                Instant.parse("2026-01-01T00:00:00Z"), owner.orgId()));
        long projectId = Api.createProject(owner, "AUTO");
        late.as().body(Map.of("projectId", projectId, "date", MONDAY.minusDays(6).toString(), "minutes", 240))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        return new Team(owner, late, away);
    }

    private void runAt(String instant) {
        TestClock.fix(Instant.parse(instant));
        try {
            reminders.run();
        } finally {
            TestClock.reset();
        }
    }

    private static List<Notification> remindersTo(Account who) {
        return QuarkusTransaction.requiringNew().call(() -> Notification.<Notification>list("userId = ?1 and type = ?2",
                who.userId(), NotificationType.TIMESHEET_REMINDER));
    }

    @Test
    void remindsOnceOnTheMondayMorningThoseWhoLoggedTimeAndDidNotSubmit() {
        Team team = team(true);
        runAt("2026-03-30T05:59:00Z");
        assertThat(remindersTo(team.late())).isEmpty();

        runAt("2026-03-30T06:00:00Z");
        runAt("2026-03-30T06:01:00Z");
        runAt("2026-03-30T09:00:00Z");
        List<Notification> sent = remindersTo(team.late());
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().weekStart).isEqualTo(MONDAY.minusWeeks(1));
        assertThat(sent.getFirst().actorId).isNull();
        assertThat(sent.getFirst().message).isEqualTo("A reminder to submit your timesheet for the week of " + MONDAY.minusWeeks(1));
        // No time logged, no reminder, as with "Remind all".
        assertThat(remindersTo(team.away())).isEmpty();
        // Not on Tuesday either: Monday was the first working day.
        runAt("2026-03-31T06:30:00Z");
        assertThat(remindersTo(team.late())).hasSize(1);
    }

    @Test
    void aRunThatFailsLeavesTheDayForTheNextOne() {
        Team team = team(true);
        reminders.failWith(people -> {
            throw new IllegalStateException("mail store down");
        });
        try {
            runAt("2026-03-30T06:00:00Z");
        } finally {
            reminders.failWith(people -> people);
        }
        assertThat(remindersTo(team.late())).isEmpty();
        runAt("2026-03-30T06:01:00Z");
        assertThat(remindersTo(team.late())).hasSize(1);
    }

    @Test
    void offByDefaultAndNotAfterNoon() {
        Team off = team(false);
        runAt("2026-03-30T06:30:00Z");
        assertThat(remindersTo(off.late())).isEmpty();

        Team late = team(true);
        runAt("2026-03-30T10:01:00Z");
        assertThat(remindersTo(late.late())).isEmpty();
    }

    @Test
    void aHolidayOnMondayMovesThemToTuesday() {
        Team team = team(true);
        team.owner().as().body(Map.of("date", MONDAY.toString(), "name", "Easter Monday"))
                .post("/api/orgs/" + team.owner().orgId() + "/holidays").then().statusCode(201);
        runAt("2026-03-30T06:30:00Z");
        assertThat(remindersTo(team.late())).isEmpty();
        runAt("2026-03-31T06:30:00Z");
        assertThat(remindersTo(team.late())).hasSize(1);

        WorkCalendar calendar = new WorkCalendar(List.of(MONDAY));
        assertThat(TimesheetService.firstWorkingDayOfWeek(calendar, MONDAY)).isFalse();
        assertThat(TimesheetService.firstWorkingDayOfWeek(calendar, MONDAY.plusDays(1))).isTrue();
        assertThat(TimesheetService.firstWorkingDayOfWeek(calendar, MONDAY.plusDays(2))).isFalse();
        assertThat(TimesheetService.firstWorkingDayOfWeek(WorkCalendar.DEFAULT, MONDAY)).isTrue();
    }
}
