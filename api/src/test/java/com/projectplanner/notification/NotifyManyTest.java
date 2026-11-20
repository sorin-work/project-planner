package com.projectplanner.notification;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Notifying several people reads their choices in one query, however many they are. */
@QuarkusTest
class NotifyManyTest {

    @Inject
    SessionFactory sessionFactory;

    /** The statements of submitting a week in an organisation with this many managers. */
    private long submitWith(int managers) {
        Account owner = Api.signup("Owner of " + managers);
        for (int i = 0; i < managers - 1; i++) {
            Api.addMember(owner, Api.signup("Manager " + i), "MANAGER");
        }
        Account member = Api.signup("Member of " + managers);
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "M" + managers);
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        member.as().body(Map.of("projectId", projectId, "date", monday.toString(), "minutes", 60))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);

        sessionFactory.getStatistics().clear();
        member.as().body(Map.of("weekStart", monday.toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit").then().statusCode(200);
        return sessionFactory.getStatistics().getPrepareStatementCount();
    }

    @Test
    void submittingAWeekToSixManagersReadsTheirChoicesInOneQuery() {
        long one = submitWith(1);
        long six = submitWith(6);
        // Five more notifications are five more inserts, and nothing else: one preference query per
        // manager would make it ten.
        assertThat(six - one).isLessThanOrEqualTo(5);
    }
}
