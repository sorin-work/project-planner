package com.projectplanner.org;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class OrgResourceTest {

    @Test
    void invitingAnExistingUserAddsThemStraightAway() {
        Account owner = Api.signup("Olga");
        Account other = Api.signup("Petra");
        owner.as().body(Map.of("email", other.email(), "role", "MEMBER"))
                .post("/api/orgs/" + owner.orgId() + "/invitations")
                .then().statusCode(201)
                .body("member.email", equalTo(other.email()))
                .body("invitation", nullValue());

        other.as().get("/api/me").then().body("organisations", hasSize(2));
        owner.as().get("/api/orgs/" + owner.orgId() + "/members")
                .then().statusCode(200).body("email", hasItem(other.email()));
    }

    @Test
    void invitedPeopleJoinWhenTheySignUp() {
        Account owner = Api.signup("Quinn");
        String email = Api.uniqueEmail("rita");
        owner.as().body(Map.of("email", email, "role", "MANAGER"))
                .post("/api/orgs/" + owner.orgId() + "/invitations")
                .then().statusCode(201)
                .body("invitation.email", equalTo(email));
        owner.as().get("/api/orgs/" + owner.orgId() + "/invitations").then().body("email", hasItem(email));

        Account rita = Api.signup("Rita", email, null);
        rita.as().get("/api/me").then()
                .body("organisations", hasSize(1))
                .body("organisations[0].organisationId", equalTo((int) owner.orgId()))
                .body("organisations[0].role", equalTo("MANAGER"));
        owner.as().get("/api/orgs/" + owner.orgId() + "/invitations").then().body("$", hasSize(0));
    }

    @Test
    void membersCannotManageTheTeam() {
        Account owner = Api.signup("Tara");
        Account member = Api.signup("Uma");
        Api.addMember(owner, member, "MEMBER");
        member.as().body(Map.of("email", Api.uniqueEmail("x"), "role", "MEMBER"))
                .post("/api/orgs/" + owner.orgId() + "/invitations")
                .then().statusCode(403);
        member.as().get("/api/orgs/" + owner.orgId()).then().statusCode(200).body("role", equalTo("MEMBER"));
    }

    @Test
    void strangersGetNotFound() {
        Account owner = Api.signup("Vera");
        Account stranger = Api.signup("Walt");
        stranger.as().get("/api/orgs/" + owner.orgId()).then().statusCode(404);
    }

    @Test
    void cannotDemoteTheLastOwner() {
        Account owner = Api.signup("Xena");
        Number memberId = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().extract().path("[0].id");
        owner.as().body(Map.of("role", "MEMBER"))
                .patch("/api/orgs/" + owner.orgId() + "/members/" + memberId)
                .then().statusCode(409).body("code", equalTo("last_owner"));
    }

    @Test
    void ratesAreOnlyVisibleToManagers() {
        Account owner = Api.signup("Yara");
        Account member = Api.signup("Zack");
        Api.addMember(owner, member, "MEMBER");
        Number memberId = owner.as().get("/api/orgs/" + owner.orgId() + "/members")
                .then().extract().path("find { it.email == '" + member.email() + "' }.id");
        owner.as().body(Map.of("hourlyRate", 85, "weeklyCapacityHours", 32))
                .patch("/api/orgs/" + owner.orgId() + "/members/" + memberId)
                .then().statusCode(200)
                .body("hourlyRate", Api.num(85))
                .body("weeklyCapacityHours", Api.num(32));
    }

    @Test
    void ownersSetRoleRates() {
        Account owner = Api.signup("Abel");
        owner.as().body(Map.of("rates", List.of(Map.of("role", "MANAGER", "hourlyRate", 100), Map.of("role", "MEMBER", "hourlyRate", 70))))
                .put("/api/orgs/" + owner.orgId() + "/role-rates")
                .then().statusCode(200).body("$", hasSize(2));
        owner.as().body(Map.of("currency", "GBP", "timezone", "Europe/London", "defaultHourlyRate", 60))
                .patch("/api/orgs/" + owner.orgId())
                .then().statusCode(200).body("currency", equalTo("GBP")).body("timezone", equalTo("Europe/London"));
    }
}
