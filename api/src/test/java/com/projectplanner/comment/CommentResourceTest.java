package com.projectplanner.comment;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CommentResourceTest {

    @Test
    void mentionsNotifyTeammates() {
        Account owner = Api.signup("Ola");
        Account member = Api.signup("Pat");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "MENTION");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Homepage"));

        owner.as().body(Map.of("body", "@" + member.handle() + " can you review? cc " + owner.email()))
                .post("/api/tasks/" + taskId + "/comments")
                .then().statusCode(201).body("authorName", equalTo("Ola"));

        member.as().get("/api/notifications/unread-count").then().body("count", equalTo(1));
        member.as().get("/api/notifications").then()
                .body("[0].type", equalTo("MENTIONED"))
                .body("[0].actorName", equalTo("Ola"))
                .body("[0].read", equalTo(false));
        // Owners aren't notified about their own comment, even though their email is in it.
        owner.as().get("/api/notifications/unread-count").then().body("count", equalTo(0));

        Number id = member.as().get("/api/notifications").then().extract().path("[0].id");
        member.as().post("/api/notifications/" + id + "/read").then().statusCode(204);
        member.as().get("/api/notifications/unread-count").then().body("count", equalTo(0));
        owner.as().post("/api/notifications/" + id + "/read").then().statusCode(404);
    }

    @Test
    void assigneesHearAboutComments() {
        Account owner = Api.signup("Quentin");
        Account member = Api.signup("Rae");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "ASSIGNEE");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Logo", "assigneeId", member.userId()));
        member.as().post("/api/notifications/read-all").then().statusCode(204);

        owner.as().body(Map.of("body", "Looks great")).post("/api/tasks/" + taskId + "/comments").then().statusCode(201);
        member.as().get("/api/notifications?unread=true").then().body("$", hasSize(1)).body("[0].type", equalTo("COMMENTED"));
    }

    @Test
    void onlyAuthorsEditComments() {
        Account owner = Api.signup("Sid");
        Account member = Api.signup("Teo");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "EDIT");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Copy"));
        Number commentId = member.as().body(Map.of("body", "First draft"))
                .post("/api/tasks/" + taskId + "/comments").then().statusCode(201).extract().path("id");

    }
}
