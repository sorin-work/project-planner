package com.projectplanner.report;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.TestClock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class TimeReportResourceTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    /** Monday of last week: a week that is over, so every day of it can take time. */
    private static final LocalDate WEEK = WorkCalendar.weekStart(TODAY).minusWeeks(1);

    private static String reports(Account owner) {
        return "/api/orgs/" + owner.orgId() + "/reports";
    }

    private static String range(LocalDate from, LocalDate to) {
        return "?from=" + from + "&to=" + to;
    }

    private static void log(Account who, Account owner, Map<String, Object> entry) {
        who.as().body(entry).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
    }

    private static Map<String, Object> entry(long projectId, LocalDate date, int minutes) {
        return Map.of("projectId", projectId, "date", date.toString(), "minutes", minutes);
    }

    /**
     * An agency with a client project and an internal one. Uli (owner, 100 an hour through the
     * organisation's default) and Vera (member, 80 an hour through her role) log time last week:
     * <pre>
     * WEB / Design   Uli   3 x 20 min  billable   3 x 33.33 = 99.99
     * WEB            Vera      90 min  billable              120.00
     * INT            Vera      60 min  not billable
     * INT            Uli       30 min  billable               50.00
     * </pre>
     */
    private record Agency(Account owner, Account member, long clientId, long web, long internal, long design) {
    }

    private static Agency agency(String ownerName, String memberName, String clientName) {
        Account owner = Api.signup(ownerName);
        Account member = Api.signup(memberName);
        Api.addMember(owner, member, "MEMBER");
        owner.as().body(Map.of("defaultHourlyRate", 100)).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        owner.as().body(Map.of("rates", List.of(Map.of("role", "MEMBER", "hourlyRate", 80))))
                .put("/api/orgs/" + owner.orgId() + "/role-rates").then().statusCode(200);
        long clientId = Api.createClient(owner, clientName);
        long web = Api.createProject(owner, "WEB", clientId);
        long internal = Api.createProject(owner, "INT");
        long design = Api.createTask(owner, web, Map.of("title", "Design"));
        for (int day = 0; day < 3; day++) {
            log(owner, owner, Map.of("projectId", web, "taskId", design, "date", WEEK.plusDays(day).toString(), "minutes", 20));
        }
        log(member, owner, entry(web, WEEK, 90));
        log(member, owner, entry(internal, WEEK.plusDays(1), 60));
        log(owner, owner, Map.of("projectId", internal, "date", WEEK.plusDays(4).toString(), "minutes", 30, "billable", true));
        return new Agency(owner, member, clientId, web, internal, design);
    }

    @Test
    void addsUpTimePerProjectClientMemberAndTask() {
        Agency a = agency("Uli", "Vera", "Acme");
        Account owner = a.owner();
        String week = range(WEEK, WEEK.plusDays(6));

        owner.as().get(reports(owner) + "/time" + week).then().statusCode(200)
                .body("from", equalTo(WEEK.toString())).body("to", equalTo(WEEK.plusDays(6).toString()))
                .body("groupBy", equalTo("PROJECT"))
                .body("rows.key", contains(String.valueOf(a.web()), String.valueOf(a.internal())))
                .body("rows.label", contains("WEB · Project WEB", "INT · Project INT"))
                .body("rows.minutes", contains(150, 90))
                .body("rows.billableMinutes", contains(150, 30))
                .body("rows[0].billableAmount", Api.num(219.99))
                .body("rows[1].billableAmount", Api.num(50.00))
                // Three entries of twenty minutes at 100 an hour are 99.99, and the total is what the rows add up to.
                .body("total.minutes", equalTo(240)).body("total.billableMinutes", equalTo(180))
                .body("total.billableAmount", Api.num(269.99));

        owner.as().get(reports(owner) + "/time" + week + "&groupBy=client").then().statusCode(200)
                .body("rows.key", contains(String.valueOf(a.clientId()), "none"))
                .body("rows.label", contains("Acme", "Internal"))
                .body("rows.minutes", contains(150, 90))
                .body("total.billableAmount", Api.num(269.99));

        owner.as().get(reports(owner) + "/time" + week + "&groupBy=member").then().statusCode(200)
                .body("rows.label", contains("Vera", "Uli"))
                .body("rows.minutes", contains(150, 90))
                .body("rows.billableMinutes", contains(90, 90))
                .body("rows[0].billableAmount", Api.num(120.00))
                .body("rows[1].billableAmount", Api.num(149.99))
                .body("total.billableAmount", Api.num(269.99));

        owner.as().get(reports(owner) + "/time" + week + "&groupBy=TASK").then().statusCode(200)
                .body("rows.label", contains("INT · Project INT · No task", "WEB · Project WEB · No task", "WEB · Project WEB · Design"))
                .body("rows.key", contains("project-" + a.internal(), "project-" + a.web(), String.valueOf(a.design())))
                .body("rows.minutes", contains(90, 90, 60))
                .body("total.billableAmount", Api.num(269.99));
    }

    @Test
    void filtersByProjectClientPersonAndBillableTime() {
        Agency a = agency("Wim", "Xena", "Globex");
        Account owner = a.owner();
        String week = reports(owner) + "/time" + range(WEEK, TODAY);
        // A timer that is running has no time yet. It would be a row of its own on this project.
        long timed = Api.createProject(owner, "TMR");
        a.member().as().body(Map.of("projectId", timed)).post("/api/orgs/" + owner.orgId() + "/timer/start").then().statusCode(201);

        owner.as().get(week).then().statusCode(200).body("rows", hasSize(2)).body("total.minutes", equalTo(240));
        // The drill-down of the page: the tasks of one project.
        owner.as().get(week + "&groupBy=task&projectId=" + a.web()).then().statusCode(200)
                .body("rows.label", contains("WEB · Project WEB · No task", "WEB · Project WEB · Design"))
                .body("total.minutes", equalTo(150)).body("total.billableAmount", Api.num(219.99));
        owner.as().get(week + "&clientId=" + a.clientId()).then().statusCode(200)
                .body("rows.label", contains("WEB · Project WEB")).body("total.minutes", equalTo(150));
        owner.as().get(week + "&userId=" + a.member().userId()).then().statusCode(200)
                .body("rows.minutes", contains(90, 60)).body("total.billableAmount", Api.num(120.00));
        owner.as().get(week + "&billable=false").then().statusCode(200)
                .body("rows.label", contains("INT · Project INT"))
                .body("total.minutes", equalTo(60)).body("total.billableMinutes", equalTo(0)).body("total.billableAmount", Api.num(0));
        owner.as().get(week + "&billable=true&groupBy=member").then().statusCode(200)
                .body("rows.minutes", contains(90, 90)).body("total.minutes", equalTo(180));
        owner.as().get(reports(owner) + "/time" + range(WEEK.minusWeeks(3), WEEK.minusDays(1))).then().statusCode(200)
                .body("rows", empty()).body("total.minutes", equalTo(0)).body("total.billableAmount", Api.num(0));
    }

    @Test
    void membersGetTheirOwnTimeWithoutMoneyAndClientViewersNothing() {
        Agency a = agency("Yara", "Zeno", "Initech");
        Account owner = a.owner();
        Account member = a.member();
        Account viewer = Api.signup("Abby");
        Account stranger = Api.signup("Bram");
        Api.addMember(owner, viewer, "CLIENT", a.clientId());
        String week = range(WEEK, WEEK.plusDays(6));

        member.as().get(reports(owner) + "/time" + week + "&groupBy=member").then().statusCode(200)
                .body("rows.label", contains("Zeno"))
                .body("rows.minutes", contains(150))
                .body("rows[0].billableAmount", nullValue())
                .body("total.minutes", equalTo(150)).body("total.billableMinutes", equalTo(90))
                .body("total.billableAmount", nullValue());
        member.as().get(reports(owner) + "/time" + week + "&userId=" + member.userId()).then().statusCode(200)
                .body("total.minutes", equalTo(150));
        // Somebody else's time is refused whether or not that person exists.
        member.as().get(reports(owner) + "/time" + week + "&userId=" + owner.userId()).then().statusCode(403);
        member.as().get(reports(owner) + "/time" + week + "&userId=999999999").then().statusCode(403);
        member.as().get(reports(owner) + "/time.csv" + week + "&userId=" + owner.userId()).then().statusCode(403);
        member.as().get(reports(owner) + "/utilisation" + week).then().statusCode(403);
        member.as().get(reports(owner) + "/utilisation.csv" + week).then().statusCode(403);

        for (String report : List.of("/time", "/time.csv", "/utilisation", "/utilisation.csv")) {
            viewer.as().get(reports(owner) + report + week).then().statusCode(403);
            stranger.as().get(reports(owner) + report + week).then().statusCode(404);
        }
    }

    @Test
    void aFilterForSomethingThatIsNotThereIsNotAnEmptyReport() {
        Agency a = agency("Cleo", "Dag", "Umbrella");
        Account owner = a.owner();
        Account other = Api.signup("Enzo");
        long theirProject = Api.createProject(other, "THEIRS");
        long theirClient = Api.createClient(other, "Their client");
        String week = reports(owner) + "/time" + range(WEEK, WEEK.plusDays(6));

        // A project, a client or a person of another organisation answers like one that does not exist.
        for (String filter : List.of("&projectId=" + theirProject, "&projectId=999999999", "&clientId=" + theirClient,
                "&clientId=999999999", "&userId=" + other.userId(), "&userId=999999999")) {
            owner.as().get(week + filter).then().statusCode(404).body("code", equalTo("not_found"));
            owner.as().get(week.replace("/time?", "/time.csv?") + filter).then().statusCode(404);
        }
        a.member().as().get(week + "&projectId=" + theirProject).then().statusCode(404);
        a.member().as().get(week + "&clientId=" + theirClient).then().statusCode(404);

        owner.as().get(week + "&groupBy=colour").then().statusCode(400).body("code", equalTo("invalid_group"));
        owner.as().get(reports(owner) + "/time?preset=yesterday").then().statusCode(400).body("code", equalTo("invalid_preset"));
        owner.as().get(reports(owner) + "/time").then().statusCode(400).body("code", equalTo("invalid_range"));
        owner.as().get(reports(owner) + "/time" + range(WEEK, WEEK.minusDays(1))).then().statusCode(400).body("code", equalTo("invalid_range"));
        owner.as().get(reports(owner) + "/utilisation" + range(WEEK.minusYears(2).minusDays(1), WEEK))
                .then().statusCode(400).body("code", equalTo("invalid_range"));
    }

    @Test
    void presetsAreWorkedOutFromTheOrganisationsDate() {
        Account owner = Api.signup("Finn");
        long projectId = Api.createProject(owner, "PRESET");
        owner.as().body(Map.of("timezone", "Pacific/Kiritimati")).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        // The last day of March and the first of April 2026, a Tuesday and a Wednesday.
        log(owner, owner, entry(projectId, LocalDate.of(2026, 3, 31), 60));
        log(owner, owner, entry(projectId, LocalDate.of(2026, 4, 1), 30));
        try {
            // Eight in the evening of 31 March in UTC is ten in the morning of 1 April on the island.
            TestClock.fix(Instant.parse("2026-03-31T20:00:00Z"));
            owner.as().get(reports(owner) + "/time?preset=this_month").then().statusCode(200)
                    .body("from", equalTo("2026-04-01")).body("to", equalTo("2026-04-30")).body("total.minutes", equalTo(30));
            owner.as().get(reports(owner) + "/time?preset=last_month").then().statusCode(200)
                    .body("from", equalTo("2026-03-01")).body("to", equalTo("2026-03-31")).body("total.minutes", equalTo(60));
            owner.as().get(reports(owner) + "/time?preset=THIS_WEEK").then().statusCode(200)
                    .body("from", equalTo("2026-03-30")).body("to", equalTo("2026-04-05")).body("total.minutes", equalTo(90));
            owner.as().get(reports(owner) + "/utilisation?preset=last_week").then().statusCode(200)
                    .body("from", equalTo("2026-03-23")).body("to", equalTo("2026-03-29")).body("workingDays", equalTo(5));
        } finally {
            TestClock.reset();
        }
    }

    @Test
    void utilisationComparesBillableTimeWithCapacityUpToToday() {
        Account owner = Api.signup("Gwen");
        Account member = Api.signup("Hugo");
        Account viewer = Api.signup("Ines");
        Api.addMember(owner, member, "MEMBER");
        long clientId = Api.createClient(owner, "Hooli");
        Api.addMember(owner, viewer, "CLIENT", clientId);
        long projectId = Api.createProject(owner, "UTIL", clientId);
        Number membershipId = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then()
                .extract().path("find { it.userId == " + member.userId() + " }.id");
        owner.as().body(Map.of("weeklyCapacityHours", 32)).patch("/api/orgs/" + owner.orgId() + "/members/" + membershipId)
                .then().statusCode(200);
        // 2026-03-09 is a Monday.
        LocalDate monday = LocalDate.of(2026, 3, 9);
        log(owner, owner, entry(projectId, monday, 300));
        log(member, owner, entry(projectId, monday, 288));
        log(member, owner, Map.of("projectId", projectId, "date", monday.plusDays(1).toString(), "minutes", 120, "billable", false));
        try {
            // Wednesday at noon. Three working days of this week have come: 24 hours for Gwen, 19.2 for Hugo.
            TestClock.fix(Instant.parse("2026-03-11T12:00:00Z"));
            owner.as().get(reports(owner) + "/utilisation?preset=this_week").then().statusCode(200)
                    .body("from", equalTo("2026-03-09")).body("to", equalTo("2026-03-15"))
                    .body("capacityTo", equalTo("2026-03-11")).body("workingDays", equalTo(3))
                    // Client viewers log no time and are not listed.
                    .body("rows.name", contains("Gwen", "Hugo"))
                    .body("rows.userId", contains((int) owner.userId(), (int) member.userId()))
                    .body("rows.minutes", contains(300, 408))
                    .body("rows.billableMinutes", contains(300, 288))
                    .body("rows.capacityMinutes", contains(1440, 1152))
                    .body("rows[0].utilisationPct", Api.num(20.8))
                    .body("rows[1].utilisationPct", Api.num(25.0))
                    .body("total.name", equalTo("Total")).body("total.userId", nullValue())
                    .body("total.minutes", equalTo(708)).body("total.billableMinutes", equalTo(588))
                    .body("total.capacityMinutes", equalTo(2592)).body("total.utilisationPct", Api.num(22.7));

            // A range that is over counts all of its working days.
            owner.as().get(reports(owner) + "/utilisation" + range(monday.minusDays(7), monday.minusDays(1))).then().statusCode(200)
                    .body("capacityTo", equalTo("2026-03-08")).body("workingDays", equalTo(5))
                    .body("rows.capacityMinutes", contains(2400, 1920)).body("rows.utilisationPct", contains(0.0f, 0.0f));
            // One that has not begun has no capacity to compare with yet.
            owner.as().get(reports(owner) + "/utilisation" + range(monday.plusDays(7), monday.plusDays(13))).then().statusCode(200)
                    .body("capacityTo", nullValue()).body("workingDays", equalTo(0))
                    .body("rows.capacityMinutes", contains(0, 0)).body("total.utilisationPct", nullValue());

            String csv = owner.as().get(reports(owner) + "/utilisation.csv?preset=this_week").then().statusCode(200)
                    .contentType(startsWith("text/csv"))
                    .header("Content-Disposition", containsString("utilisation-2026-03-09-to-2026-03-15.csv"))
                    .extract().asString();
            assertThat(csv.split("\r\n")).containsExactly(
                    "from,to,member,minutes,billable_minutes,capacity_minutes,utilisation_pct",
                    "2026-03-09,2026-03-15,Gwen,300,300,1440,20.8",
                    "2026-03-09,2026-03-15,Hugo,408,288,1152,25.0");
        } finally {
            TestClock.reset();
        }
    }

    @Test
    void theCsvHasTheRowsOfThePageAndIsSafeToOpen() {
        Agency a = agency("Jules", "Kai", "=cmd|' /C calc'!A0");
        Account owner = a.owner();
        String week = range(WEEK, WEEK.plusDays(6));

        String byClient = owner.as().get(reports(owner) + "/time.csv" + week + "&groupBy=client").then().statusCode(200)
                .contentType(startsWith("text/csv"))
                .header("Content-Disposition", containsString("time-by-client-" + WEEK + "-to-" + WEEK.plusDays(6) + ".csv"))
                .extract().asString();
        // Dates in ISO form, whole minutes, a plain decimal for money, and a name that starts like a formula made harmless.
        assertThat(byClient.split("\r\n")).containsExactly(
                "from,to,group_by,key,label,minutes,billable_minutes,billable_amount",
                WEEK + "," + WEEK.plusDays(6) + ",client," + a.clientId() + ",'=cmd|' /C calc'!A0,150,150,219.99",
                WEEK + "," + WEEK.plusDays(6) + ",client,none,Internal,90,30,50.00");

        // Members get the same columns without the money, and only their own time.
        String own = a.member().as().get(reports(owner) + "/time.csv" + week).then().statusCode(200).extract().asString();
        assertThat(own.split("\r\n")).containsExactly(
                "from,to,group_by,key,label,minutes,billable_minutes",
                WEEK + "," + WEEK.plusDays(6) + ",project," + a.web() + ",WEB · Project WEB,90,90",
                WEEK + "," + WEEK.plusDays(6) + ",project," + a.internal() + ",INT · Project INT,60,0");
    }
}
