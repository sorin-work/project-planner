package com.projectplanner.report;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ReportResourceTest {

    private static final LocalDate MONDAY = WorkCalendar.weekStart(LocalDate.now());

    @Test
    void healthReportsProgressBudgetAndOverdueWork() {
        Account owner = Api.signup("Val");
        Account member = Api.signup("Wyn");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "HEALTH");
        long done = Api.createTask(owner, projectId, Map.of("title", "Done", "estimateHours", 30,
                "startDate", MONDAY.minusWeeks(3).toString(), "dueDate", MONDAY.minusWeeks(2).toString()));
        Api.createTask(owner, projectId, Map.of("title", "Late", "estimateHours", 10,
                "startDate", MONDAY.minusWeeks(2).toString(), "dueDate", MONDAY.minusWeeks(1).toString()));
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + done).then().statusCode(200);
        owner.as().body(Map.of("projectId", projectId, "date", MONDAY.minusWeeks(2).toString(), "minutes", 600, "billable", true))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);

        owner.as().get("/api/projects/" + projectId + "/health").then().statusCode(200)
                .body("progress.progressPct", Api.num(75))
                .body("progress.overdueTasks", equalTo(1))
                .body("overdue", hasSize(1))
                .body("overdue[0].title", equalTo("Late"))
                .body("budget.loggedHours", Api.num(10))
                .body("budget.hoursBurnPct", Api.num(10))
                .body("budget.spentAmount", notNullValue())
                .body("status", equalTo("OFF_TRACK"));
    }

    @Test
    void activityFeed() {
        Account owner = Api.signup("Zoe");
        Api.createProject(owner, "FEED");
        owner.as().get("/api/orgs/" + owner.orgId() + "/activity").then().statusCode(200)
                .body("[0].action", equalTo("project.created"))
                .body("[0].actorName", equalTo("Zoe"));
    }
}
