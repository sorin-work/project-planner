package com.projectplanner.project;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

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
                .body("[0].name", equalTo("Website"));
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
