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

    /** Matches any JSON number equal to {@code expected}, whatever its scale (100, 100.0, 100.00). */
    public static org.hamcrest.Matcher<Object> num(double expected) {
        return new org.hamcrest.TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(Object item) {
                return item instanceof Number n && Math.abs(n.doubleValue() - expected) < 0.0001;
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("a number equal to ").appendValue(expected);
            }
        };
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

    /** Adds an existing account to {@code owner}'s organisation with the given role. */
    public static void addMember(Account owner, Account member, String role) {
        addMember(owner, member, role, null);
    }

    public static void addMember(Account owner, Account member, String role, Long clientId) {
        var body = new java.util.HashMap<String, Object>(Map.of("email", member.email(), "role", role));
        if (clientId != null) {
            body.put("clientId", clientId);
        }
        owner.as().body(body).post("/api/orgs/" + owner.orgId() + "/invitations").then().statusCode(201);
    }

    public static long createProject(Account owner, String code) {
        return createProject(owner, code, null);
    }

    public static long createProject(Account owner, String code, Long clientId) {
        var body = new java.util.HashMap<String, Object>(Map.of("name", "Project " + code, "code", code,
                "status", "ACTIVE", "budgetHours", 100, "budgetAmount", 10000));
        if (clientId != null) {
            body.put("clientId", clientId);
        }
        Number id = owner.as().body(body).post("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    public static long createTask(Account who, long projectId, Map<String, Object> fields) {
        Number id = who.as().body(fields).post("/api/projects/" + projectId + "/tasks")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    public static long createClient(Account owner, String name) {
        Number id = owner.as().body(Map.of("name", name)).post("/api/orgs/" + owner.orgId() + "/clients")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }
}
