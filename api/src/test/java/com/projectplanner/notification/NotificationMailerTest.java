package com.projectplanner.notification;

import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.Mailbox;
import com.projectplanner.support.TestClock;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class NotificationMailerTest {

    @Inject
    NotificationMailer mailer;

    @Inject
    MockMailbox mockMailbox;

    @Inject
    MailOutbox outbox;

    private Mailbox mailbox;

    @BeforeEach
    void setUp() {
        mailbox = new Mailbox(mockMailbox, outbox);
        // Whatever other tests left waiting goes out first, so that each test sees only its own.
        mailer.run();
        mockMailbox.clear();
    }

    @AfterEach
    void resetClock() {
        TestClock.reset();
    }

    /** The notification mails to the address. Being added to the organisation is a mail of its own. */
    private List<Mail> mails(String address) {
        return mailbox.to(address).stream().filter(m -> m.getSubject().contains("notification")).toList();
    }

    private Mail only(String address) {
        List<Mail> mails = mails(address);
        assertThat(mails).as("notification mails to %s", address).hasSize(1);
        return mails.get(0);
    }

    /** An owner, a member and a task with a title that must not leave the app. */
    private record Team(Account owner, Account member, long projectId, long taskId) {
    }

    private static Team team() {
        Account owner = Api.signup("Olivia Owner");
        Account member = Api.signup("Mark Member");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "SECRET");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Secret merger plan"));
        return new Team(owner, member, projectId, taskId);
    }

    private static void assign(Team team) {
        team.owner().as().body(Map.of("assigneeId", team.member().userId())).patch("/api/tasks/" + team.taskId())
                .then().statusCode(200);
    }

    private static void mention(Team team) {
        team.owner().as().body(Map.of("body", "Have a look, @" + team.member().handle() + " (the merger is on)"))
                .post("/api/tasks/" + team.taskId() + "/comments").then().statusCode(201);
    }

    @Test
    void aPersonsNotificationsGoOutInOneMailWithFixedWordsAndLinks() {
        Team team = team();
        assign(team);
        mention(team);

        assertThat(mailer.run()).isEqualTo(1);
        Mail mail = only(team.member().email());
        String link = "http://planner.test/projects/" + team.projectId() + "/list?task=" + team.taskId();
        assertThat(mail.getSubject()).isEqualTo("2 new notifications on project-planner");
        assertThat(mail.getText()).contains("A task was assigned to you.").contains("You were mentioned in a comment.")
                .contains(link).contains("http://planner.test/settings").contains("http://planner.test/unsubscribe#token=");
        assertThat(mail.getHtml()).contains("href=\"" + link + "\"");
        // Nothing anybody typed: no title, no comment, no name.
        assertThat(mail.getSubject() + mail.getText() + mail.getHtml())
                .doesNotContain("Secret").doesNotContain("merger").doesNotContain("Olivia").doesNotContain("SECRET");

        assertThat(mailer.run()).as("nothing is sent twice").isZero();
        assertThat(mails(team.member().email())).hasSize(1);
        // The owner only got what is not mailed by default.
        assertThat(mails(team.owner().email())).isEmpty();
    }

    @Test
    void whatWasReadBeforeTheJobRanIsNotMailed() {
        Team team = team();
        assign(team);
        team.member().as().post("/api/notifications/read-all").then().statusCode(204);

        assertThat(mailer.run()).isZero();
        assertThat(mails(team.member().email())).isEmpty();
    }

    @Test
    void eachTypeIsMailedShownOnlyInTheAppOrNotAtAll() {
        Team team = team();
        team.member().as().body(Map.of("preferences", List.of(
                        Map.of("type", "MENTIONED", "delivery", "IN_APP"), Map.of("type", "ASSIGNED", "delivery", "OFF"))))
                .put("/api/me/notification-preferences").then().statusCode(200)
                .body("mailAvailable", equalTo(true))
                .body("preferences.find { it.type == 'MENTIONED' }.delivery", equalTo("IN_APP"))
                .body("preferences.find { it.type == 'ASSIGNED' }.delivery", equalTo("OFF"))
                .body("preferences.find { it.type == 'TIMESHEET_REJECTED' }.delivery", equalTo("EMAIL"))
                .body("preferences.find { it.type == 'COMMENTED' }.delivery", equalTo("IN_APP"));
        assign(team);
        mention(team);

        assertThat(mailer.run()).isZero();
        team.member().as().get("/api/notifications").then().statusCode(200)
                .body("type", equalTo(List.of("MENTIONED")));
    }

    @Test
    void aChoiceMadeAfterTheNotificationStillHoldsWhenTheJobRuns() {
        Team team = team();
        mention(team);
        team.member().as().body(Map.of("preferences", List.of(Map.of("type", "MENTIONED", "delivery", "IN_APP"))))
                .put("/api/me/notification-preferences").then().statusCode(200);

        assertThat(mailer.run()).isZero();
    }

    @Test
    void nothingIsMailedAboutWhatThePersonCanNoLongerSee() {
        Team team = team();
        assign(team);
        // Made a client viewer of a client the project does not belong to.
        long clientId = Api.createClient(team.owner(), "Another client");
        Number memberId = team.owner().as().get("/api/orgs/" + team.owner().orgId() + "/members").then().extract()
                .path("find { it.userId == " + team.member().userId() + " }.id");
        team.owner().as().body(Map.of("role", "CLIENT", "clientId", clientId))
                .patch("/api/orgs/" + team.owner().orgId() + "/members/" + memberId).then().statusCode(200);

        assertThat(mailer.run()).isZero();
        assertThat(mails(team.member().email())).isEmpty();
    }

    @Test
    void aMailThatFailsIsTriedAgainLaterAndLaterAndThenGoes() {
        Team team = team();
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        assign(team);

        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean(true);
        QuarkusMock.installMockForType(new MailOutbox(null) {
            @Override
            public boolean deliver(String kind, MailTemplateInstance mail) {
                attempts.incrementAndGet();
                return !failing.get() && super.deliver(kind, mail);
            }
        }, MailOutbox.class);
        assertThat(mailer.run()).isZero();
        assertThat(attempts).hasValue(1);
        TestClock.fix(start.plusSeconds(30));
        mailer.run();
        assertThat(attempts).as("not before a minute").hasValue(1);
        TestClock.fix(start.plusSeconds(61));
        mailer.run();
        assertThat(attempts).hasValue(2);
        TestClock.fix(start.plusSeconds(61 + 90));
        mailer.run();
        assertThat(attempts).as("then not before two minutes").hasValue(2);

        failing.set(false);
        TestClock.fix(start.plusSeconds(61 + 121));
        assertThat(mailer.run()).isEqualTo(1);
        assertThat(mails(team.member().email())).hasSize(1);
    }

    @Test
    void theStopLinkInTheMailEndsMailsOfThatTypeWithoutSigningIn() {
        Team team = team();
        mention(team);
        mailer.run();
        String token = Mailbox.tokenOfLink(only(team.member().email()), "/unsubscribe");

        given().contentType(ContentType.JSON).body(Map.of("token", token)).post("/api/auth/unsubscribe")
                .then().statusCode(200).body("type", equalTo("MENTIONED"));
        team.member().as().get("/api/me/notification-preferences").then()
                .body("preferences.find { it.type == 'MENTIONED' }.delivery", equalTo("IN_APP"))
                .body("preferences.find { it.type == 'ASSIGNED' }.delivery", equalTo("EMAIL"));

        // The token cannot be bent to another person or another type.
        String other = token.replace(token.substring(0, token.indexOf('.')),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("unsubscribe:" + team.owner().userId() + ":MENTIONED").getBytes()));
        given().contentType(ContentType.JSON).body(Map.of("token", other)).post("/api/auth/unsubscribe")
                .then().statusCode(400).body("code", equalTo("invalid_link"));
        given().contentType(ContentType.JSON).body(Map.of("token", "made.up")).post("/api/auth/unsubscribe")
                .then().statusCode(400).body("code", equalTo("invalid_link"));
    }

    @Test
    void notificationsFromBeforeMailWasOnAreNeverMailed() {
        Team team = team();
        assign(team);
        // As the migration leaves them on an upgraded install.
        QuarkusTransaction.requiringNew().run(() ->
                Notification.update("emailedAt = createdAt where userId = ?1", team.member().userId()));
        assertThat(mailer.run()).isZero();
    }

    private static Number memberId(Account owner, Account who) {
        return owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().extract()
                .path("find { it.userId == " + who.userId() + " }.id");
    }

    @Test
    void approvalsAreNotMailedToSomebodyWhoIsNoLongerAManager() {
        Account owner = Api.signup("Odile Owner");
        Account manager = Api.signup("Manny Manager");
        Account member = Api.signup("Mo Member");
        Api.addMember(owner, manager, "MANAGER");
        Api.addMember(owner, member, "MEMBER");
        manager.as().body(Map.of("preferences", List.of(Map.of("type", "TIMESHEET_SUBMITTED", "delivery", "EMAIL"))))
                .put("/api/me/notification-preferences").then().statusCode(200);
        long projectId = Api.createProject(owner, "WEEK");
        java.time.LocalDate monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        member.as().body(Map.of("projectId", projectId, "date", monday.toString(), "minutes", 60))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        member.as().body(Map.of("weekStart", monday.toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit").then().statusCode(200);

        owner.as().body(Map.of("role", "MEMBER")).patch("/api/orgs/" + owner.orgId() + "/members/" + memberId(owner, manager))
                .then().statusCode(200);

        mailer.run();
        assertThat(mails(manager.email())).isEmpty();
    }

    @Test
    void nothingIsMailedToSomebodyWhoLeftTheOrganisation() {
        Team team = team();
        assign(team);
        team.owner().as().delete("/api/orgs/" + team.owner().orgId() + "/members/" + memberId(team.owner(), team.member()))
                .then().statusCode(204);

        mailer.run();
        assertThat(mails(team.member().email())).isEmpty();
    }

    @Test
    void nothingIsMailedAboutATaskThatWasDeleted() {
        Team team = team();
        assign(team);
        team.owner().as().delete("/api/tasks/" + team.taskId()).then().statusCode(204);

        mailer.run();
        assertThat(mails(team.member().email())).isEmpty();
    }

    @Test
    void aNotificationThatAnotherRunHasTakenOnWaitsUntilItsClaimRunsOut() {
        Team team = team();
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        assign(team);
        // As a run on another instance would leave it, or one that died while it sent.
        QuarkusTransaction.requiringNew().run(() -> Notification.update("emailClaimedUntil = ?1 where userId = ?2",
                start.plus(NotificationMailer.CLAIM), team.member().userId()));

        assertThat(mailer.run()).isZero();
        TestClock.fix(start.plus(NotificationMailer.CLAIM).plusSeconds(1));
        assertThat(mailer.run()).isEqualTo(1);
        assertThat(mails(team.member().email())).hasSize(1);
    }

    @Test
    void twoRunsAtTheSameMomentSendOneMail() throws Exception {
        Team team = team();
        assign(team);
        AtomicInteger delivered = new AtomicInteger();
        QuarkusMock.installMockForType(new MailOutbox(null) {
            @Override
            public boolean deliver(String kind, MailTemplateInstance mail) {
                delivered.incrementAndGet();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        }, MailOutbox.class);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(mailer::run);
            var second = pool.submit(mailer::run);
            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(delivered).hasValue(1);
    }

    @Test
    void readingANotificationWhileItsMailIsOnItsWayIsNotUndone() {
        Team team = team();
        assign(team);
        QuarkusMock.installMockForType(new MailOutbox(null) {
            @Override
            public boolean deliver(String kind, MailTemplateInstance mail) {
                // "Mark all as read" while the mail server takes its time, and then fails.
                team.member().as().post("/api/notifications/read-all").then().statusCode(204);
                return false;
            }
        }, MailOutbox.class);

        mailer.run();
        team.member().as().get("/api/notifications/unread-count").then().body("count", equalTo(0));
    }
}
