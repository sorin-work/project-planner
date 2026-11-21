package com.projectplanner.report;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ScheduleIssueCountTest {

    private static long project(Account owner, String code, String endDate, Long clientId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Project " + code, "code", code, "status", "ACTIVE", "endDate", endDate));
        if (clientId != null) {
            body.put("clientId", clientId);
        }
        Number id = owner.as().body(body).post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static long task(Account owner, long projectId, String title, String start, String due) {
        return Api.createTask(owner, projectId, Map.of("title", title, "startDate", start, "dueDate", due));
    }

    private static String inList(long projectId) {
        return "find { it.project.id == " + projectId + " }.health.scheduleIssues";
    }

    @Test
    void theProjectListAndTheOverviewCountWhatTheTimelineLists() {
        Account owner = Api.signup("Ivo");
        Account member = Api.signup("Jade");
        Account viewer = Api.signup("Kai");
        long clientId = Api.createClient(owner, "Acme");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", clientId);
        long broken = project(owner, "BROKEN", "2026-03-13", clientId);
        long fine = project(owner, "FINE", "2026-03-31", null);

        // Build waits for Design. Moved by hand to start before Design is due, and Launch runs past the end.
        long design = task(owner, broken, "Design", "2026-03-02", "2026-03-04");
        long build = task(owner, broken, "Build", "2026-03-05", "2026-03-06");
        owner.as().body(Map.of("predecessorId", design)).post("/api/tasks/" + build + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("startDate", "2026-03-03", "dueDate", "2026-03-04")).patch("/api/tasks/" + build).then().statusCode(200);
        task(owner, broken, "Launch", "2026-03-16", "2026-03-16");
        task(owner, fine, "Plan", "2026-03-02", "2026-03-03");

        owner.as().get("/api/projects/" + broken + "/schedule-issues").then().statusCode(200).body("$", hasSize(2));
        String list = "/api/orgs/" + owner.orgId() + "/projects";
        owner.as().get(list).then().statusCode(200).body(inList(broken), equalTo(2)).body(inList(fine), equalTo(0));
        member.as().get(list).then().statusCode(200).body(inList(broken), equalTo(2));
        // A client viewer sees their client's project, with its count, and no other.
        viewer.as().get(list).then().statusCode(200).body("$", hasSize(1)).body(inList(broken), equalTo(2));
        owner.as().get("/api/projects/" + broken + "/health").then().statusCode(200).body("scheduleIssues", equalTo(2));
        viewer.as().get("/api/projects/" + broken + "/health").then().statusCode(200).body("scheduleIssues", equalTo(2));
        // The dashboard does not count them.
        owner.as().get("/api/orgs/" + owner.orgId() + "/dashboard").then().statusCode(200)
                .body("activeProjects[0].health.scheduleIssues", nullValue());

        // A finished task is not an issue any more, and the critical path keeps it where it was done.
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + build).then().statusCode(200);
        owner.as().get(list).then().statusCode(200).body(inList(broken), equalTo(1));
        owner.as().get("/api/projects/" + broken + "/critical-path").then().statusCode(200)
                .body("finish", equalTo("2026-03-16"))
                .body("tasks.find { it.taskId == " + build + " }.earlyStart", equalTo("2026-03-03"))
                .body("tasks.find { it.taskId == " + build + " }.floatDays", nullValue())
                .body("tasks.find { it.taskId == " + build + " }.critical", equalTo(false));
    }
}
