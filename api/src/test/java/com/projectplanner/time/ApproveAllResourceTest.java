package com.projectplanner.time;

import com.projectplanner.activity.ActivityEntry;
import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class ApproveAllResourceTest {

    private static final LocalDate WEEK = WorkCalendar.weekStart(LocalDate.now(ZoneOffset.UTC)).minusWeeks(1);

    private static String path(Account owner) {
        return "/api/orgs/" + owner.orgId() + "/timesheets";
    }

    private static void logAndSubmit(Account who, Account owner, long projectId, LocalDate week) {
        who.as().body(Map.of("projectId", projectId, "date", week.toString(), "minutes", 120))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        who.as().body(Map.of("weekStart", week.toString())).post(path(owner) + "/submit").then().statusCode(200);
    }

    /** The approval queue as the page has it: each week's id and submittedAt. */
    private static List<Map<String, Object>> queue(Account manager, Account owner) {
        JsonPath json = manager.as().get(path(owner)).then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (Map<String, Object> s : json.<Map<String, Object>>getList("$")) {
            sheets.add(Map.of("id", s.get("id"), "submittedAt", s.get("submittedAt")));
        }
        return sheets;
    }

    private static int sheetId(Account owner, Account who) {
        return QuarkusTransaction.requiringNew().call(() -> Timesheet.find(owner.orgId(), who.userId(), WEEK).orElseThrow().id).intValue();
    }

    @Test
    void approvesWhatWasOnScreenAndSaysWhyTheRestWasLeftOut() {
        Account owner = Api.signup("Ola");
        Account manager = Api.signup("Max");
        Account ann = Api.signup("Ann");
        Account ben = Api.signup("Ben");
        Account cy = Api.signup("Cy");
        Account dee = Api.signup("Dee");
        Api.addMember(owner, manager, "MANAGER");
        for (Account member : List.of(ann, ben, cy, dee)) {
            Api.addMember(owner, member, "MEMBER");
        }
        long projectId = Api.createProject(owner, "BULK");
        for (Account who : List.of(manager, ann, ben, cy)) {
            logAndSubmit(who, owner, projectId, WEEK);
        }
        List<Map<String, Object>> onScreen = queue(manager, owner);
        assertThat(onScreen).hasSize(4);

        // After the page loaded: Ben withdraws, Cy withdraws and submits again, Dee submits for the first time.
        ben.as().body(Map.of("weekStart", WEEK.toString())).post(path(owner) + "/withdraw").then().statusCode(200);
        cy.as().body(Map.of("weekStart", WEEK.toString())).post(path(owner) + "/withdraw").then().statusCode(200);
        cy.as().body(Map.of("weekStart", WEEK.toString())).post(path(owner) + "/submit").then().statusCode(200);
        logAndSubmit(dee, owner, projectId, WEEK);

        List<Map<String, Object>> request = new ArrayList<>(onScreen);
        request.add(Map.of("id", 999_999_999, "submittedAt", "2026-01-05T09:00:00Z"));
        manager.as().body(Map.of("sheets", request)).post(path(owner) + "/approve").then().statusCode(200)
                .body("approved", contains(sheetId(owner, ann)))
                .body("skipped", hasSize(4))
                .body("skipped.find { it.timesheetId == " + sheetId(owner, manager) + " }.reason", equalTo("OWN_WEEK"))
                .body("skipped.find { it.timesheetId == " + sheetId(owner, ben) + " }.reason", equalTo("NOT_SUBMITTED"))
                .body("skipped.find { it.timesheetId == " + sheetId(owner, cy) + " }.reason", equalTo("CHANGED"))
                .body("skipped.find { it.timesheetId == 999999999 }.reason", equalTo("NOT_FOUND"));

        // Dee was not on screen and is still waiting; so are Cy's new submission and the manager's own week.
        assertThat(QuarkusTransaction.requiringNew().call(() -> Timesheet.statusOf(owner.orgId(), dee.userId(), WEEK)))
                .isEqualTo(TimesheetStatus.SUBMITTED);
        assertThat(QuarkusTransaction.requiringNew().call(() -> Timesheet.statusOf(owner.orgId(), cy.userId(), WEEK)))
                .isEqualTo(TimesheetStatus.SUBMITTED);

        // The owner may approve the manager's week, and the same click twice approves nothing new.
        owner.as().body(Map.of("sheets", onScreen)).post(path(owner) + "/approve").then().statusCode(200)
                .body("approved", contains(sheetId(owner, manager)))
                .body("skipped.reason", contains("NOT_SUBMITTED", "NOT_SUBMITTED", "CHANGED"));
    }

    @Test
    void eachPersonHearsOfTheirWeekAndTheLogHasALinePerWeek() {
        Account owner = Api.signup("Pat");
        Account ann = Api.signup("Ana");
        Account bo = Api.signup("Bo");
        Api.addMember(owner, ann, "MEMBER");
        Api.addMember(owner, bo, "MEMBER");
        long projectId = Api.createProject(owner, "NOTE");
        logAndSubmit(ann, owner, projectId, WEEK);
        logAndSubmit(ann, owner, projectId, WEEK.minusWeeks(1));
        logAndSubmit(bo, owner, projectId, WEEK);

        owner.as().body(Map.of("sheets", queue(owner, owner))).post(path(owner) + "/approve").then().statusCode(200)
                .body("approved", hasSize(3)).body("skipped", empty());

        ann.as().get("/api/notifications").then().statusCode(200)
                .body("type", contains("TIMESHEET_APPROVED", "TIMESHEET_APPROVED"))
                .body("weekStart", containsInAnyOrder(WEEK.toString(), WEEK.minusWeeks(1).toString()))
                .body("find { it.weekStart == '" + WEEK + "' }.message",
                        equalTo("Pat approved your timesheet for the week of " + WEEK));
        assertThat(QuarkusTransaction.requiringNew().call(() -> Notification.count("userId = ?1 and type = ?2",
                bo.userId(), NotificationType.TIMESHEET_APPROVED))).isEqualTo(1);
        List<String> lines = QuarkusTransaction.requiringNew().call(() -> ActivityEntry.<ActivityEntry>list(
                "organisationId = ?1 and action = ?2 order by id", owner.orgId(), "timesheet.approved")
                .stream().map(e -> e.summary).toList());
        assertThat(lines).containsExactlyInAnyOrder(
                "Approved Ana's timesheet for the week of " + WEEK,
                "Approved Ana's timesheet for the week of " + WEEK.minusWeeks(1),
                "Approved Bo's timesheet for the week of " + WEEK);
    }

    @Test
    void onlyManagersApproveAndOnlyInTheirOrganisation() {
        Account owner = Api.signup("Quy");
        Account member = Api.signup("Rex");
        Account viewer = Api.signup("Sue");
        Account stranger = Api.signup("Tia");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, "Acme"));
        long projectId = Api.createProject(owner, "PERM");
        logAndSubmit(member, owner, projectId, WEEK);
        List<Map<String, Object>> onScreen = queue(owner, owner);

        member.as().body(Map.of("sheets", onScreen)).post(path(owner) + "/approve").then().statusCode(403);
        viewer.as().body(Map.of("sheets", onScreen)).post(path(owner) + "/approve").then().statusCode(403);
        stranger.as().body(Map.of("sheets", onScreen)).post(path(owner) + "/approve").then().statusCode(404);
        // A week of another organisation is not found from the stranger's own one.
        stranger.as().body(Map.of("sheets", onScreen)).post(path(stranger) + "/approve").then().statusCode(200)
                .body("approved", empty()).body("skipped.reason", contains("NOT_FOUND"));
        owner.as().body(Map.of("sheets", List.of())).post(path(owner) + "/approve").then().statusCode(400)
                .body("code", equalTo("validation_failed"));
        owner.as().body(Map.of("sheets", List.of(Map.of("id", onScreen.getFirst().get("id"))))).post(path(owner) + "/approve")
                .then().statusCode(400).body("code", equalTo("validation_failed"));
        // 200 weeks at most: one more is refused as a whole, and nothing is approved.
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(onScreen.getFirst());
        }
        owner.as().body(Map.of("sheets", tooMany)).post(path(owner) + "/approve").then().statusCode(400)
                .body("code", equalTo("validation_failed"));
        assertThat(QuarkusTransaction.requiringNew().call(() -> Timesheet.statusOf(owner.orgId(), member.userId(), WEEK)))
                .isEqualTo(TimesheetStatus.SUBMITTED);
    }

    @Test
    void aWeekSentTwiceIsApprovedOnceAndAnsweredOnce() {
        Account owner = Api.signup("Uli");
        Account member = Api.signup("Val");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "TWICE");
        logAndSubmit(member, owner, projectId, WEEK);
        Map<String, Object> week = queue(owner, owner).getFirst();

        owner.as().body(Map.of("sheets", List.of(week, week, week))).post(path(owner) + "/approve").then().statusCode(200)
                .body("approved", contains(sheetId(owner, member))).body("skipped", empty());
        assertThat(QuarkusTransaction.requiringNew().call(() -> Notification.count("userId = ?1 and type = ?2",
                member.userId(), NotificationType.TIMESHEET_APPROVED))).isEqualTo(1);
        assertThat(QuarkusTransaction.requiringNew().call(() -> ActivityEntry.count("organisationId = ?1 and action = ?2",
                owner.orgId(), "timesheet.approved"))).isEqualTo(1);
    }
}
