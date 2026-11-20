package com.projectplanner.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/** The interactive page of the API reference, with the setting that turns it on. */
@QuarkusTest
@TestProfile(ApiDocsPageTest.DocsOn.class)
class ApiDocsPageTest {

    public static class DocsOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("API_DOCS", "true");
        }
    }

    @Test
    void thePageIsServedWithoutATokenAndReadsTheDocument() {
        given().get("/api/docs").then().statusCode(200).contentType(ContentType.HTML).body(containsString("/api/openapi"));
        given().get("/api/openapi").then().statusCode(200);
    }
}
