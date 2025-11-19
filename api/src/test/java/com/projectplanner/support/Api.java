package com.projectplanner.support;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** Small helpers for API tests: sign people up and make authenticated requests. */
public final class Api {

    private Api() {
    }

    public record Account(String token, long userId, String email, String handle, long orgId) {
        public RequestSpecification as() {
            return given().auth().oauth2(token).contentType(ContentType.JSON);
        }
    }

    public static String uniqueEmail(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Signs up a new user with their own organisation (or joins invited ones). */
    public static Account signup(String name) {
        return signup(name, uniqueEmail(name.toLowerCase().replace(' ', '.')), "Org of " + name);
    }

    public static Account signup(String name, String email, String organisationName) {
        var body = new java.util.HashMap<String, Object>(Map.of("name", name, "email", email, "password", "correct-horse"));
        if (organisationName != null) {
            body.put("organisationName", organisationName);
        }
        var response = given().contentType(ContentType.JSON).body(body)
                .post("/api/auth/signup").then().statusCode(201).extract();
        String token = response.path("token");
        long userId = ((Number) response.path("user.id")).longValue();
        String handle = response.path("user.handle");
        Number orgId = given().auth().oauth2(token).get("/api/me").then().statusCode(200)
                .extract().path("organisations[0].organisationId");
        return new Account(token, userId, email, handle, orgId == null ? -1 : orgId.longValue());
    }
}
