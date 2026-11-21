package com.projectplanner.common;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.LargeOrganisation;
import com.projectplanner.support.LargeOrganisation.Org;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.hamcrest.Matcher;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;

/**
 * How many statements an endpoint sends, on an organisation of fifty projects with a thousand
 * tasks and twenty thousand entries on one of them. The bounds are what the endpoints send
 * today, and none of them depends on how many projects, tasks, entries, people or weeks there
 * are. Counting is exact and does not depend on the machine, where a timing would. A change
 * that loads something per row shows up here as a count over its bound.
 * <p>
 * Every signed-in request also sends one statement of its own before the endpoint runs: the
 * session check in {@code CurrentUser.id()}, which reads the account's token version. The
 * bounds below include it, so "under ten" for the project list is nine of the endpoint's own
 * plus that one.
 */
@QuarkusTest
class QueryCountTest {

    @Inject
    SessionFactory sessionFactory;

    private Org org;

    @BeforeEach
    void organisation() {
        org = LargeOrganisation.get();
    }

    /** The statements one request sends, after a first request that warms up what is cached. */
    private long statements(Account who, Function<RequestSpecification, io.restassured.response.Response> request) {
        request.apply(who.as()).then().statusCode(anyOf2xx());
        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();
        request.apply(who.as()).then().statusCode(anyOf2xx());
        return statistics.getPrepareStatementCount();
    }

    private static Matcher<Integer> anyOf2xx() {
        return both(greaterThanOrEqualTo(200)).and(lessThan(300));
    }

    private String orgPath(String rest) {
        return "/api/orgs/" + org.owner().orgId() + rest;
    }

    @Test
    void theProjectListIsUnderTenStatementsForEveryRole() {
        // The roadmap's target. The projects, the organisation, the clients, the tasks of all
        // projects, the rates (three), the holidays and the time of all projects, besides the caller
        // and the session check.
        for (Account who : java.util.List.of(org.owner(), org.members().getFirst(), org.clientViewer())) {
            assertThat(statements(who, r -> r.get(orgPath("/projects")))).isLessThanOrEqualTo(10);
        }
        assertThat(statements(org.owner(), r -> r.get(orgPath("/projects/options")))).isLessThanOrEqualTo(4);
    }

    @Test
    void theDashboardDoesNotGrowWithTheProjects() {
        // 24 since the missing timesheets count capacity per week: the time off of last week is one more.
        assertThat(statements(org.owner(), r -> r.get(orgPath("/dashboard")))).isLessThanOrEqualTo(24);
    }

    @Test
    void peopleAreLookedUpTogether() {
        // Thirty-six weeks in the queue, five members, five organisations, a comment that mentions somebody.
        // The comment also reads how the people it notifies want to hear of it, in one query for all.
        assertThat(statements(org.owner(), r -> r.get(orgPath("/timesheets")))).isLessThanOrEqualTo(9);
        assertThat(statements(org.owner(), r -> r.get(orgPath("/members")))).isLessThanOrEqualTo(4);
        assertThat(statements(org.owner(), r -> r.get("/api/me"))).isLessThanOrEqualTo(4);
        assertThat(statements(org.owner(), r -> r.body(Map.of("body", "Over to @" + org.manager().handle()))
                .post("/api/tasks/" + org.bigProjectTaskId() + "/comments"))).isLessThanOrEqualTo(12);
    }

    @Test
    void approvingManyWeeksReadsAsMuchAsApprovingOne() {
        Account owner = Api.signup("Bulk Owner");
        long projectId = Api.createProject(owner, "BULKQ");
        java.time.LocalDate week = com.projectplanner.schedule.WorkCalendar.weekStart(
                java.time.LocalDate.now(java.time.ZoneOffset.UTC)).minusWeeks(1);
        String path = "/api/orgs/" + owner.orgId() + "/timesheets";
        for (int i = 0; i < 6; i++) {
            Account member = Api.signup("Bulk Member " + i);
            Api.addMember(owner, member, "MEMBER");
            member.as().body(Map.of("projectId", projectId, "date", week.toString(), "minutes", 60))
                    .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
            member.as().body(Map.of("weekStart", week.toString())).post(path + "/submit").then().statusCode(200);
        }
        java.util.List<Map<String, Object>> queue = owner.as().get(path).then().statusCode(200).extract().jsonPath()
                .<Map<String, Object>>getList("$").stream()
                .map(s -> Map.<String, Object>of("id", s.get("id"), "submittedAt", s.get("submittedAt"))).toList();

        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();
        owner.as().body(Map.of("sheets", queue.subList(0, 1))).post(path + "/approve").then().statusCode(200);
        long one = statistics.getPrepareStatementCount();
        statistics.clear();
        owner.as().body(Map.of("sheets", queue.subList(1, 6))).post(path + "/approve").then().statusCode(200);
        long five = statistics.getPrepareStatementCount();

        // The session check, the membership, the weeks with their lock, the people and their
        // notification choices, and per week only what is written: its notification and its
        // line in the activity log (the updates go out as one batch).
        assertThat(one).isLessThanOrEqualTo(9);
        assertThat(five - one).isEqualTo(4 * 2);
    }

    @Test
    void aSmallOrganisationSendsAsManyAsALargeOne() {
        Account small = Api.signup("Sam Small");
        Api.createProject(small, "ONE");
        Api.createProject(small, "TWO", Api.createClient(small, "Small client"));
        String path = "/api/orgs/" + small.orgId();
        assertThat(statements(small, r -> r.get(path + "/projects")))
                .isEqualTo(statements(org.owner(), r -> r.get(orgPath("/projects"))));
    }
}
