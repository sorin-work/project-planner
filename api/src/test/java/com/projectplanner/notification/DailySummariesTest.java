package com.projectplanner.notification;

import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.Mailbox;
import com.projectplanner.support.TestClock;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DailySummariesTest {

    /** Monday 30 March 2026, the day after Berlin moved to summer time: eight there is six in UTC. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 30);

    @Inject
    DailySummaries summaries;

    @Inject
    MockMailbox mockMailbox;

    @Inject
    MailOutbox outbox;

    private Mailbox mailbox;

    @BeforeEach
    void setUp() {
        mailbox = new Mailbox(mockMailbox, outbox);
    }

    @AfterEach
    void resetClock() {
        TestClock.reset();
    }

    private List<Mail> summariesTo(Account who) {
        return mailbox.to(who.email()).stream().filter(m -> m.getSubject().equals("Your morning on project-planner")).toList();
    }

    /** An organisation in Berlin: an owner, and a member with a task due on the Monday, one overdue and a week to review. */
    private record Team(Account owner, Account member, Account quiet, long projectId) {
    }

    private static Team team() {
        Account owner = Api.signup("Sven Owner");
        Account member = Api.signup("Mia Member");
        Account quiet = Api.signup("Quinn Quiet");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, quiet, "MEMBER");
        owner.as().body(Map.of("timezone", "Europe/Berlin")).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        long projectId = Api.createProject(owner, "MORNING");
        for (String due : List.of(MONDAY.toString(), MONDAY.minusDays(3).toString(), MONDAY.plusDays(2).toString())) {
            Api.createTask(owner, projectId, Map.of("title", "Secret plan " + due, "assigneeId", member.userId(), "dueDate", due));
        }
        member.as().body(Map.of("projectId", projectId, "date", MONDAY.minusDays(7).toString(), "minutes", 60))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        member.as().body(Map.of("weekStart", MONDAY.minusDays(7).toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit").then().statusCode(200);
        // Members get no summary unless they ask for one; quinn has nothing to say and asks too.
        for (Account who : List.of(member, quiet)) {
            who.as().body(Map.of("preferences", List.of(Map.of("type", "DAILY_SUMMARY", "delivery", "EMAIL"))))
                    .put("/api/me/notification-preferences").then().statusCode(200);
        }
        return new Team(owner, member, quiet, projectId);
    }

    /** One round at that moment. The clock goes back afterwards, since the sign-in tokens are checked against it. */
    private void runAt(String instant) {
        TestClock.fix(Instant.parse(instant));
        try {
            summaries.run();
        } finally {
            TestClock.reset();
        }
    }

    @Test
    void goesOutAtEightInTheOrganisationsZoneWithCountsAndLinksOnly() {
        Team team = team();

        runAt("2026-03-30T05:59:00Z");
        assertThat(summariesTo(team.member())).isEmpty();

        runAt("2026-03-30T06:00:00Z");
        Mail mail = summariesTo(team.member()).getFirst();
        String myTasks = "http://planner.test/my-tasks?org=" + team.owner().orgId();
        assertThat(mail.getText()).contains("1 of your tasks is due today.").contains("1 of your tasks is overdue.")
                .contains(myTasks).doesNotContain("review").contains("http://planner.test/unsubscribe#token=");
        assertThat(mail.getSubject() + mail.getText() + mail.getHtml()).doesNotContain("Secret").doesNotContain("Sven")
                .doesNotContain("MORNING");
        Mail owners = summariesTo(team.owner()).getFirst();
        assertThat(owners.getText()).contains("1 timesheet is waiting for your review.")
                .contains("http://planner.test/approvals?org=" + team.owner().orgId()).doesNotContain("tasks");
        // Nothing to say, no mail; and nobody gets a second one that day.
        assertThat(summariesTo(team.quiet())).isEmpty();
        runAt("2026-03-30T09:30:00Z");
        assertThat(summariesTo(team.member())).hasSize(1);
        assertThat(summariesTo(team.owner())).hasSize(1);
    }

    @Test
    void aMorningMissedAtEightIsCaughtUpUntilNoonButNotAfter() {
        Team late = team();
        runAt("2026-03-30T09:45:00Z");
        assertThat(summariesTo(late.member())).hasSize(1);

        Team missed = team();
        runAt("2026-03-30T10:01:00Z");
        assertThat(summariesTo(missed.member())).isEmpty();
    }

    @Test
    void noSummaryOnAHolidayOrAWeekend() {
        Team team = team();
        team.owner().as().body(Map.of("date", MONDAY.toString(), "name", "Easter Monday"))
                .post("/api/orgs/" + team.owner().orgId() + "/holidays").then().statusCode(201);
        runAt("2026-03-30T06:30:00Z");
        runAt("2026-03-29T06:30:00Z");
        assertThat(summariesTo(team.member())).isEmpty();
        assertThat(summariesTo(team.owner())).isEmpty();
    }

    @Test
    void theStopLinkAndTheSettingTurnItOff() {
        Team team = team();
        team.owner().as().body(Map.of("preferences", List.of(Map.of("type", "DAILY_SUMMARY", "delivery", "IN_APP"))))
                .put("/api/me/notification-preferences").then().statusCode(200);
        team.owner().as().get("/api/me/notification-preferences").then().statusCode(200)
                .body("preferences.find { it.type == 'DAILY_SUMMARY' }.delivery", org.hamcrest.Matchers.equalTo("OFF"));
        runAt("2026-03-30T06:00:00Z");
        assertThat(summariesTo(team.owner())).isEmpty();

        String token = Mailbox.tokenOfLink(summariesTo(team.member()).getFirst(), "/unsubscribe");
        io.restassured.RestAssured.given().contentType("application/json").body(Map.of("token", token))
                .post("/api/auth/unsubscribe").then().statusCode(200).body("type", org.hamcrest.Matchers.equalTo("DAILY_SUMMARY"));
        team.member().as().get("/api/me/notification-preferences").then().statusCode(200)
                .body("preferences.find { it.type == 'DAILY_SUMMARY' }.delivery", org.hamcrest.Matchers.equalTo("OFF"));
    }

    @Test
    void twoInstancesAtTheSameMomentSendOneSummary() throws Exception {
        Team team = team();
        TestClock.fix(Instant.parse("2026-03-30T06:05:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> run = () -> summaries.run();
            Future<Integer> first = pool.submit(run);
            Future<Integer> second = pool.submit(run);
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(summariesTo(team.member())).hasSize(1);
        assertThat(summariesTo(team.owner())).hasSize(1);
        assertThat(QuarkusTransaction.requiringNew().call(() -> DailySummary.count("organisationId = ?1 and userId = ?2",
                team.owner().orgId(), team.member().userId()))).isEqualTo(1);
        // The row a run wrote first decides: with it in place, nobody sends.
        assertThat(summaries.claim(team.owner().orgId(), team.quiet().userId(), MONDAY)).isFalse();
    }
}
