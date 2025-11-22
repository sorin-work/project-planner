package com.projectplanner.auth;

import com.projectplanner.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class AuthResourceTest {

    @Test
    void signupCreatesAnOrganisationWithTheUserAsOwner() {
        String email = Api.uniqueEmail("ana");
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("name", "Ana Pop", "email", email, "password", "correct-horse", "organisationName", "Pop Studio"))
                .post("/api/auth/signup")
                .then().statusCode(201)
                .body("token", notNullValue())
                .body("user.email", equalTo(email))
                .extract().path("token");

        given().auth().oauth2(token).get("/api/me")
                .then().statusCode(200)
                .body("user.name", equalTo("Ana Pop"))
                .body("organisations", hasSize(1))
                .body("organisations[0].organisationName", equalTo("Pop Studio"))
                .body("organisations[0].role", equalTo("OWNER"));
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() {
        Api.Account account = Api.signup("Ben");
        given().contentType(ContentType.JSON)
                .body(Map.of("email", account.email().toUpperCase(), "password", "correct-horse"))
                .post("/api/auth/login")
                .then().statusCode(200)
                .body("user.id", equalTo((int) account.userId()));
    }

    @Test
    void rejectsWrongPassword() {
        Api.Account account = Api.signup("Cara");
        given().contentType(ContentType.JSON)
                .body(Map.of("email", account.email(), "password", "wrong-password"))
                .post("/api/auth/login")
                .then().statusCode(401)
                .body("code", equalTo("invalid_credentials"));
    }

    @Test
    void rejectsDuplicateEmail() {
        Api.Account account = Api.signup("Dan");
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "Dan Again", "email", account.email(), "password", "correct-horse"))
                .post("/api/auth/signup")
                .then().statusCode(409)
                .body("code", equalTo("email_taken"));
    }

    @Test
    void protectedEndpointsNeedAToken() {
        given().get("/api/me").then().statusCode(401).body("code", equalTo("unauthorized"));
        given().auth().oauth2("not.a.token").get("/api/me").then().statusCode(401);
    }

    @Test
    void healthIsUp() {
        given().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }
}
