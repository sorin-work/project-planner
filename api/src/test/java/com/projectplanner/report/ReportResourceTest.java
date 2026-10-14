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
        member.as().get("/api/projects/" + projectId + "/health").then().statusCode(200)
                .body("budget.loggedHours", Api.num(10))
                .body("budget.spentAmount", nullValue());
    }

    @Test
    void workloadComparesAllocationWithCapacity() {
        Account owner = Api.signup("Xan");
        long projectId = Api.createProject(owner, "LOAD");
        Number memberId = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().extract().path("[0].id");
        owner.as().body(Map.of("weeklyCapacityHours", 20)).patch("/api/orgs/" + owner.orgId() + "/members/" + memberId)
                .then().statusCode(200);
        Api.createTask(owner, projectId, Map.of("title", "Big job", "estimateHours", 30, "assigneeId", owner.userId(),
                "startDate", MONDAY.plusWeeks(1).toString(), "dueDate", MONDAY.plusWeeks(1).plusDays(4).toString()));

        owner.as().get("/api/orgs/" + owner.orgId() + "/workload?weeks=3&from=" + MONDAY).then().statusCode(200)
                .body("members", hasSize(1))
                .body("members[0].weeks", hasSize(3))
                .body("members[0].weeks[0].allocatedHours", Api.num(0))
                .body("members[0].weeks[1].allocatedHours", Api.num(30))
                .body("members[0].weeks[1].overallocated", equalTo(true))
                .body("tasks[0].title", equalTo("Big job"));
        owner.as().get("/api/orgs/" + owner.orgId() + "/workload?weeks=99").then().statusCode(400);
    }

    @Test
    void dashboardShowsMyWork() {
        Account owner = Api.signup("Yul");
        long projectId = Api.createProject(owner, "DASH");
        Api.createTask(owner, projectId, Map.of("title", "Overdue thing", "assigneeId", owner.userId(),
                "dueDate", LocalDate.now().minusDays(3).toString()));
        Api.createTask(owner, projectId, Map.of("title", "Soon thing", "assigneeId", owner.userId(),
                "dueDate", LocalDate.now().plusDays(2).toString()));
        owner.as().get("/api/orgs/" + owner.orgId() + "/dashboard").then().statusCode(200)
                .body("overdue[0].title", equalTo("Overdue thing"))
                .body("dueSoon[0].title", equalTo("Soon thing"))
                .body("activeProjects", hasSize(1));
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
