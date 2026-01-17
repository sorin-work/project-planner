package com.projectplanner.project;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ProjectResourceTest {

    @Test
    void createsAndListsProjects() {
        Account owner = Api.signup("Bea");
        long clientId = Api.createClient(owner, "Acme");
        owner.as().body(Map.of("name", "Website", "code", "acme-web", "clientId", clientId,
                        "startDate", "2026-03-02", "endDate", "2026-05-29", "budgetHours", 200, "budgetAmount", 16000))
                .post("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(201)
                .body("code", equalTo("ACME-WEB"))
                .body("status", equalTo("PLANNED"))
                .body("clientName", equalTo("Acme"))
                .body("color", equalTo("#4f6bed"));

        owner.as().get("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].project.name", equalTo("Website"))
                .body("[0].health.status", notNullValue());
    }

    @Test
    void projectCodesAreUniquePerOrganisation() {
        Account owner = Api.signup("Cleo");
        Api.createProject(owner, "DUP");
        owner.as().body(Map.of("name", "Other", "code", "dup"))
                .post("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(409).body("code", equalTo("code_taken"));
    }

    @Test
    void validatesDatesAndCode() {
        Account owner = Api.signup("Dora");
        owner.as().body(Map.of("name", "Bad", "code", "has spaces"))
                .post("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(400).body("errors.field", hasItem("code"));
        owner.as().body(Map.of("name", "Bad dates", "code", "BAD", "startDate", "2026-05-01", "endDate", "2026-04-01"))
                .post("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(400).body("code", equalTo("invalid_dates"));
    }

    @Test
    void membersCanReadButNotCreateAndDontSeeMoney() {
        Account owner = Api.signup("Edna");
        Account member = Api.signup("Finn");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "MONEY");

        member.as().body(Map.of("name", "Nope", "code", "NOPE"))
                .post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(403);
        member.as().get("/api/projects/" + projectId)
                .then().statusCode(200)
                .body("budgetHours", Api.num(100))
                .body("budgetAmount", nullValue());
        owner.as().get("/api/projects/" + projectId).then().body("budgetAmount", Api.num(10000));
    }

    @Test
    void clientViewersOnlySeeTheirClientsProjects() {
        Account owner = Api.signup("Gail");
        long acme = Api.createClient(owner, "Acme");
        long globex = Api.createClient(owner, "Globex");
        long acmeProject = Api.createProject(owner, "ACME", acme);
        long globexProject = Api.createProject(owner, "GLX", globex);
        Account viewer = Api.signup("Hank");
        Api.addMember(owner, viewer, "CLIENT", acme);

        viewer.as().get("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(200)
                .body("project.id", hasItem((int) acmeProject))
                .body("project.id", not(hasItem((int) globexProject)));
        viewer.as().get("/api/projects/" + globexProject).then().statusCode(404);
        viewer.as().body(Map.of("title", "Sneaky"))
                .post("/api/projects/" + acmeProject + "/tasks").then().statusCode(403);
    }

    @Test
    void deletingAClientWithProjectsArchivesIt() {
        Account owner = Api.signup("Jade");
        long clientId = Api.createClient(owner, "Keep me");
        Api.createProject(owner, "KEEP", clientId);
        owner.as().delete("/api/orgs/" + owner.orgId() + "/clients/" + clientId).then().statusCode(204);
        owner.as().get("/api/orgs/" + owner.orgId() + "/clients/" + clientId)
                .then().statusCode(200).body("archived", equalTo(true));
        owner.as().get("/api/orgs/" + owner.orgId() + "/clients").then().body("$", hasSize(0));
    }
}
