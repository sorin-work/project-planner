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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class MissingTimesheetsResourceTest {

    /** Last week: new organisations are in UTC, and that week is over. */
    private static final LocalDate WEEK = WorkCalendar.weekStart(LocalDate.now(ZoneOffset.UTC)).minusWeeks(1);

    private static String missing(Account owner) {
        return "/api/orgs/" + owner.orgId() + "/timesheets/missing";
    }

    private static String remind(Account owner) {
        return "/api/orgs/" + owner.orgId() + "/timesheets/remind";
    }

    private static Map<String, Object> reminder(LocalDate week, Account... people) {
        return Map.of("weekStart", week.toString(), "userIds", List.of(people).stream().map(Account::userId).toList());
    }

    private static void logTime(Account who, Account owner, long projectId, LocalDate date, int minutes) {
        who.as().body(Map.of("projectId", projectId, "date", date.toString(), "minutes", minutes))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
    }

    private static Number submit(Account who, Account owner, LocalDate week) {
        return who.as().body(Map.of("weekStart", week.toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit").then().statusCode(200).extract().path("timesheetId");
    }

    /** Accounts made in a test joined just now. Moves everybody in the organisation back to before the weeks under test. */
    private static void joinedLongAgo(Account owner) {
        QuarkusTransaction.requiringNew().run(() -> Membership.update("createdAt = ?1 where organisationId = ?2",
                Instant.now().minus(Duration.ofDays(60)), owner.orgId()));
    }

    private static long reminders(Account who) {
        return QuarkusTransaction.requiringNew().call(() -> Notification.count("userId = ?1 and type = ?2",
                who.userId(), NotificationType.TIMESHEET_REMINDER));
    }

    @Test
    void listsDraftAndSentBackWeeksWithTheTimeLogged() {
        Account owner = Api.signup("Olga");
        Account draft = Api.signup("Drew");
        Account sentBack = Api.signup("Bea");
        Account submitted = Api.signup("Sub");
        Account away = Api.signup("Avi");
        for (Account member : List.of(draft, sentBack, submitted, away)) {
            Api.addMember(owner, member, "MEMBER");
        }
        joinedLongAgo(owner);
        Account newcomer = Api.signup("Nia");
        Api.addMember(owner, newcomer, "MEMBER");
        long projectId = Api.createProject(owner, "MISS");
        logTime(draft, owner, projectId, WEEK, 90);
        logTime(draft, owner, projectId, WEEK.plusDays(6), 30);
        logTime(draft, owner, projectId, WEEK.plusDays(7), 600);
        logTime(sentBack, owner, projectId, WEEK.plusDays(1), 480);
        logTime(submitted, owner, projectId, WEEK, 60);
        Number sheetId = submit(sentBack, owner, WEEK);
        owner.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/reject").then().statusCode(200);
        submit(submitted, owner, WEEK);

        owner.as().queryParam("weekStart", WEEK.plusDays(3).toString()).get(missing(owner))
                .then().statusCode(200)
                .body("weekStart", equalTo(WEEK.toString()))
                .body("latestWeekStart", equalTo(WEEK.toString()))
                .body("people.userName", contains("Avi", "Bea", "Drew", "Olga"))
                .body("people.status", contains("DRAFT", "REJECTED", "DRAFT", "DRAFT"))
                .body("people.totalMinutes", contains(0, 480, 120, 0))
                .body("people[2].userId", equalTo((int) draft.userId()))
                .body("people[2].capacityHours", Api.num(40))
                .body("people[2].away", equalTo(false))
                .body("people[2].remindedToday", equalTo(false));
        // Without a week it is the last one that is over.
        owner.as().get(missing(owner)).then().statusCode(200)
                .body("weekStart", equalTo(WEEK.toString())).body("people", hasSize(4));

        // The dashboard counts the late weeks that have time in them, for managers only.
        owner.as().get("/api/orgs/" + owner.orgId() + "/dashboard").then().statusCode(200).body("missingTimesheets", equalTo(2));
        draft.as().get("/api/orgs/" + owner.orgId() + "/dashboard").then().statusCode(200).body("missingTimesheets", equalTo(0));
    }

    @Test
    void capacityIsTheWeeksAsInTheWorkload() {
        Account owner = Api.signup("Cora");
        Account partTime = Api.signup("Pip");
        Account away = Api.signup("Abe");
        Api.addMember(owner, partTime, "MEMBER");
        Api.addMember(owner, away, "MEMBER");
        joinedLongAgo(owner);
        long projectId = Api.createProject(owner, "CAP");
        String org = "/api/orgs/" + owner.orgId();
        owner.as().body(Map.of("weeklyCapacityHours", 20)).patch(org + "/members/" + memberId(owner, partTime)).then().statusCode(200);
        // A holiday on the Wednesday for everybody, a day off on the Thursday for Pip, and the whole week off for Abe.
        owner.as().body(Map.of("date", WEEK.plusDays(2).toString(), "name", "Feast")).post(org + "/holidays").then().statusCode(201);
        partTime.as().body(Map.of("firstDay", WEEK.plusDays(3).toString(), "lastDay", WEEK.plusDays(3).toString()))
                .post(org + "/time-off").then().statusCode(201);
        away.as().body(Map.of("firstDay", WEEK.minusDays(2).toString(), "lastDay", WEEK.plusDays(8).toString()))
                .post(org + "/time-off").then().statusCode(201);
        // Abe worked a little anyway, so he is late and not only away.
        logTime(away, owner, projectId, WEEK.plusDays(1), 60);

        owner.as().queryParam("weekStart", WEEK.toString()).get(missing(owner)).then().statusCode(200)
                .body("people.userName", contains("Abe", "Cora", "Pip"))
                .body("people.capacityHours", contains(Api.num(0), Api.num(32), Api.num(12)))
                .body("people.away", contains(true, false, false))
                .body("people[0].totalMinutes", equalTo(60));
    }

    private static Object memberId(Account owner, Account member) {
        return owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().statusCode(200)
                .extract().path("find { it.userId == " + member.userId() + " }.id");
    }

    @Test
    void aWeekThatIsNotOverIsNotLate() {
        Account owner = Api.signup("Pia");
        Account member = Api.signup("Quin");
        Api.addMember(owner, member, "MEMBER");
        LocalDate thisWeek = WEEK.plusWeeks(1);

        owner.as().queryParam("weekStart", thisWeek.toString()).get(missing(owner))
                .then().statusCode(400).body("code", equalTo("week_not_over"));
        owner.as().body(reminder(thisWeek.plusDays(2), member)).post(remind(owner))
                .then().statusCode(400).body("code", equalTo("week_not_over"));
        owner.as().body(Map.of("weekStart", WEEK.toString(), "userIds", List.of())).post(remind(owner))
                .then().statusCode(400).body("code", equalTo("validation_failed"));
        assertThat(reminders(member)).isZero();
    }

    @Test
    void onlyManagersSeeWhoIsLateAndSendReminders() {
        Account owner = Api.signup("Rae");
        Account manager = Api.signup("Sol");
        Account member = Api.signup("Tess");
        Account late = Api.signup("Uma");
        Account viewer = Api.signup("Vic");
        Account stranger = Api.signup("Wes");
        Api.addMember(owner, manager, "MANAGER");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, late, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, "Acme"));
        joinedLongAgo(owner);

        member.as().get(missing(owner)).then().statusCode(403);
        viewer.as().get(missing(owner)).then().statusCode(403);
        stranger.as().get(missing(owner)).then().statusCode(404);
        member.as().body(reminder(WEEK, late)).post(remind(owner)).then().statusCode(403);
        viewer.as().body(reminder(WEEK, late)).post(remind(owner)).then().statusCode(403);
        stranger.as().body(reminder(WEEK, late)).post(remind(owner)).then().statusCode(404);
        assertThat(reminders(late)).isZero();

        // Client viewers log no time, so they are never late.
        manager.as().get(missing(owner)).then().statusCode(200).body("people.userName", contains("Rae", "Sol", "Tess", "Uma"));
        manager.as().body(reminder(WEEK, late)).post(remind(owner))
                .then().statusCode(200).body("reminded", contains((int) late.userId()));
    }

    @Test
    void remindsSomebodyOncePerWeekAndDay() {
        Account owner = Api.signup("Xia");
        Account manager = Api.signup("Yan");
        Account late = Api.signup("Zed");
        Api.addMember(owner, manager, "MANAGER");
        Api.addMember(owner, late, "MEMBER");
        joinedLongAgo(owner);
        logTime(late, owner, Api.createProject(owner, "REMIND"), WEEK, 240);

        owner.as().body(reminder(WEEK, late)).post(remind(owner)).then().statusCode(200)
                .body("weekStart", equalTo(WEEK.toString()))
                .body("reminded", contains((int) late.userId()))
                .body("skipped", empty());
        late.as().get("/api/notifications").then().statusCode(200)
                .body("[0].type", equalTo("TIMESHEET_REMINDER"))
                .body("[0].message", equalTo("Xia asked you to submit your timesheet for the week of " + WEEK))
                .body("[0].weekStart", equalTo(WEEK.toString()))
                .body("[0].actorName", equalTo("Xia"));
        owner.as().get(missing(owner)).then()
                .body("people.find { it.userId == " + late.userId() + " }.remindedToday", equalTo(true));

        // Neither the same manager nor another one can remind again today, however often the name is sent.
        owner.as().body(reminder(WEEK, late, late, late)).post(remind(owner)).then().statusCode(200)
                .body("reminded", empty())
                .body("skipped", hasSize(1))
                .body("skipped[0].userId", equalTo((int) late.userId()))
                .body("skipped[0].reason", equalTo("ALREADY_REMINDED"));
        manager.as().body(reminder(WEEK, late)).post(remind(owner)).then().statusCode(200)
                .body("reminded", empty()).body("skipped.reason", contains("ALREADY_REMINDED"));
        assertThat(reminders(late)).isEqualTo(1);

        // Another week is another reminder, and so is the same week a day later.
        owner.as().body(reminder(WEEK.minusWeeks(1), late)).post(remind(owner)).then().statusCode(200)
                .body("reminded", contains((int) late.userId()));
        QuarkusTransaction.requiringNew().run(() -> Notification.update("createdAt = ?1 where userId = ?2 and type = ?3",
                Instant.now().minus(Duration.ofDays(1)), late.userId(), NotificationType.TIMESHEET_REMINDER));
        manager.as().body(reminder(WEEK, late)).post(remind(owner)).then().statusCode(200)
                .body("reminded", contains((int) late.userId()));
        assertThat(reminders(late)).isEqualTo(3);
    }

    @Test
    void stopsAfterAFewRemindersADay() {
        Account owner = Api.signup("Gia");
        Account late = Api.signup("Hank");
        Api.addMember(owner, late, "MEMBER");
        joinedLongAgo(owner);

        for (int weeksBack = 0; weeksBack < MissingTimesheets.MAX_REMINDERS_PER_DAY; weeksBack++) {
            owner.as().body(reminder(WEEK.minusWeeks(weeksBack), late)).post(remind(owner)).then().statusCode(200)
                    .body("reminded", contains((int) late.userId()));
        }
        owner.as().body(reminder(WEEK.minusWeeks(MissingTimesheets.MAX_REMINDERS_PER_DAY), late)).post(remind(owner))
                .then().statusCode(200)
                .body("reminded", empty()).body("skipped.reason", contains("DAILY_LIMIT"));
        assertThat(reminders(late)).isEqualTo(MissingTimesheets.MAX_REMINDERS_PER_DAY);
    }

    @Test
    void theOrganisationsDateDecidesNotTheServers() {
        // 2026-03-02 is a Monday. Kiritimati is fourteen hours ahead of UTC.
        LocalDate week = LocalDate.of(2026, 3, 2);
        Instant sundayEveningUtc = Instant.parse("2026-03-08T20:00:00Z");
        Account owner = Api.signup("Ivo");
        Account late = Api.signup("Jun");
        Api.addMember(owner, late, "MEMBER");
        owner.as().body(Map.of("timezone", "Pacific/Kiritimati")).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        QuarkusTransaction.requiringNew().run(() -> Membership.update("createdAt = ?1 where organisationId = ?2",
                Instant.parse("2026-01-05T09:00:00Z"), owner.orgId()));
        try {
            // Still Sunday for the server, already Monday morning on the island: the week is over there.
            TestClock.fix(sundayEveningUtc);
            owner.as().queryParam("weekStart", week.toString()).get(missing(owner)).then().statusCode(200)
                    .body("latestWeekStart", equalTo(week.toString()))
                    .body("people.userName", contains("Ivo", "Jun"));
            owner.as().queryParam("weekStart", week.plusWeeks(1).toString()).get(missing(owner))
                    .then().statusCode(400).body("code", equalTo("week_not_over"));
            owner.as().body(reminder(week, late)).post(remind(owner)).then().statusCode(200)
                    .body("reminded", contains((int) late.userId()));

            // Six hours on it is Monday for the server too, but the island has not changed days.
            TestClock.fix(sundayEveningUtc.plus(Duration.ofHours(6)));
            owner.as().body(reminder(week, late)).post(remind(owner)).then().statusCode(200)
                    .body("skipped.reason", contains("ALREADY_REMINDED"));
            owner.as().queryParam("weekStart", week.toString()).get(missing(owner)).then()
                    .body("people.find { it.userId == " + late.userId() + " }.remindedToday", equalTo(true));

            // Nine hours later the server's day is the same, and on the island it is Tuesday.
            TestClock.fix(sundayEveningUtc.plus(Duration.ofHours(15)));
            owner.as().queryParam("weekStart", week.toString()).get(missing(owner)).then()
                    .body("people.find { it.userId == " + late.userId() + " }.remindedToday", equalTo(false));
            owner.as().body(reminder(week, late)).post(remind(owner)).then().statusCode(200)
                    .body("reminded", contains((int) late.userId()));
        } finally {
            TestClock.reset();
        }
        assertThat(reminders(late)).isEqualTo(2);
    }

    @Test
    void leavesOutPeopleWhoseWeekIsNotMissing() {
        Account owner = Api.signup("Abe");
        Account late = Api.signup("Bell");
        Account submitted = Api.signup("Cho");
        Account viewer = Api.signup("Dot");
        Account stranger = Api.signup("Eve");
        Api.addMember(owner, late, "MEMBER");
        Api.addMember(owner, submitted, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, "Globex"));
        joinedLongAgo(owner);
        Account newcomer = Api.signup("Finn");
        Api.addMember(owner, newcomer, "MEMBER");
        long projectId = Api.createProject(owner, "SKIP");
        logTime(late, owner, projectId, WEEK, 60);
        logTime(submitted, owner, projectId, WEEK, 60);
        submit(submitted, owner, WEEK);

        owner.as().body(reminder(WEEK, owner, late, submitted, viewer, stranger, newcomer)).post(remind(owner))
                .then().statusCode(200)
                .body("reminded", contains((int) late.userId()))
                .body("skipped.userId", containsInAnyOrder((int) owner.userId(), (int) submitted.userId(),
                        (int) viewer.userId(), (int) stranger.userId(), (int) newcomer.userId()))
                .body("skipped.find { it.userId == " + owner.userId() + " }.reason", equalTo("YOURSELF"))
                .body("skipped.find { it.userId == " + submitted.userId() + " }.reason", equalTo("NOT_MISSING"))
                .body("skipped.find { it.userId == " + stranger.userId() + " }.reason", equalTo("NOT_MISSING"));
        for (Account other : List.of(owner, submitted, viewer, stranger, newcomer)) {
            assertThat(reminders(other)).isZero();
        }
    }
}
