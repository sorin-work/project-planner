package com.projectplanner.invoice;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.time.TimeEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class InvoiceResourceTest {

    /** Two weeks ago: over, and approved below. Last week stays unapproved. */
    private static final LocalDate WEEK = WorkCalendar.weekStart(LocalDate.now(ZoneOffset.UTC)).minusWeeks(2);

    /** An agency with a client, a project at 100 an hour and a fixed price one, and a week of approved time. */
    private record Agency(Account owner, Account member, long clientId, long projectId, long fixedId, long designId, long buildId) {

        String path(String rest) {
            return "/api/orgs/" + owner.orgId() + "/invoices" + rest;
        }
    }

    private static long project(Account owner, String code, long clientId, Integer rate, Integer budgetAmount) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Project " + code, "code", code, "status", "ACTIVE", "clientId", clientId));
        if (rate != null) {
            body.put("hourlyRate", rate);
        }
        if (budgetAmount != null) {
            body.put("budgetAmount", budgetAmount);
        }
        Number id = owner.as().body(body).post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static void log(Account who, Account owner, long projectId, Long taskId, LocalDate date, int minutes, boolean billable) {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", projectId, "date", date.toString(), "minutes", minutes,
                "billable", billable));
        if (taskId != null) {
            body.put("taskId", taskId);
        }
        who.as().body(body).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
    }

    private static void approve(Account who, Account owner, LocalDate week) {
        Number sheet = who.as().body(Map.of("weekStart", week.toString())).post("/api/orgs/" + owner.orgId() + "/timesheets/submit")
                .then().statusCode(200).extract().path("timesheetId");
        owner.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheet + "/approve").then().statusCode(200);
    }

    private static Agency agency(String code) {
        Account owner = Api.signup("Ines Invoice");
        Account member = Api.signup("Mats Member");
        Api.addMember(owner, member, "MEMBER");
        long clientId = Api.createClient(owner, "Acme " + code);
        long projectId = project(owner, code, clientId, 100, null);
        long fixedId = project(owner, code + "-FIX", clientId, null, 5000);
        long design = Api.createTask(owner, projectId, Map.of("title", "Design"));
        long build = Api.createTask(owner, projectId, Map.of("title", "=HYPERLINK(\"http://evil\")"));
        // Seven minutes twice at 100 an hour: 11.67 each, priced entry by entry, so 23.34 and not 23.33.
        log(member, owner, projectId, design, WEEK, 7, true);
        log(member, owner, projectId, design, WEEK.plusDays(1), 7, true);
        log(member, owner, projectId, build, WEEK.plusDays(2), 90, true);
        log(member, owner, projectId, build, WEEK.plusDays(2), 60, false);
        log(member, owner, fixedId, null, WEEK.plusDays(3), 120, true);
        approve(member, owner, WEEK);
        // Last week is logged and submitted but not approved: not invoiced.
        log(member, owner, projectId, design, WEEK.plusWeeks(1), 60, true);
        member.as().body(Map.of("weekStart", WEEK.plusWeeks(1).toString())).post("/api/orgs/" + owner.orgId() + "/timesheets/submit")
                .then().statusCode(200);
        return new Agency(owner, member, clientId, projectId, fixedId, design, build);
    }

    private static Map<String, Object> period(Agency a) {
        return Map.of("clientId", a.clientId(), "from", WEEK.toString(), "to", WEEK.plusWeeks(1).plusDays(6).toString(),
                "taxRatePercent", 20);
    }

    @Test
    void aDraftIsTheApprovedBillableTimeAndAddsUpToTheReport() {
        Agency a = agency("INV");
        var draft = a.owner().as().body(period(a)).post(a.path("")).then().statusCode(201)
                .body("status", equalTo("DRAFT")).body("number", nullValue()).body("currency", equalTo("EUR"))
                .body("lines", hasSize(2))
                .body("lines.description", containsInAnyOrder("Design", "=HYPERLINK(\"http://evil\")"))
                .body("lines.find { it.description == 'Design' }.minutes", equalTo(14))
                .body("lines.find { it.description == 'Design' }.rateCents", equalTo(10000))
                .body("lines.find { it.description == 'Design' }.amountCents", equalTo(2334))
                .body("lines.find { it.description != 'Design' }.amountCents", equalTo(15000))
                .body("subtotalCents", equalTo(17334)).body("taxCents", equalTo(3467)).body("totalCents", equalTo(20801))
                .body("leftOut[0].projectId", equalTo((int) a.fixedId()))
                .extract();

        // The report of the same entries says the same, to the cent.
        a.owner().as().queryParam("from", WEEK.toString()).queryParam("to", WEEK.plusWeeks(1).plusDays(6).toString())
                .queryParam("projectId", a.projectId()).queryParam("billable", true).queryParam("approvedOnly", true)
                .get("/api/orgs/" + a.owner().orgId() + "/reports/time").then().statusCode(200)
                .body("total.billableAmount", Api.num(173.34));
        // Without the filter the unapproved hour of last week counts as well.
        a.owner().as().queryParam("from", WEEK.toString()).queryParam("to", WEEK.plusWeeks(1).plusDays(6).toString())
                .queryParam("projectId", a.projectId()).queryParam("billable", true)
                .get("/api/orgs/" + a.owner().orgId() + "/reports/time").then().statusCode(200)
                .body("total.billableAmount", Api.num(273.34));

        // The same time is never on two invoices.
        a.owner().as().body(period(a)).post(a.path("")).then().statusCode(409).body("code", equalTo("nothing_to_invoice"));
        long id = draft.<Number>path("id").longValue();
        assertThat(QuarkusTransaction.requiringNew().call(() -> TimeEntry.count("projectId = ?1 and invoiceLineId is not null",
                a.projectId()))).isEqualTo(3);

        // A line taken off releases its time, which a new draft takes.
        Number line = draft.path("lines.find { it.description == 'Design' }.id");
        a.owner().as().delete(a.path("/" + id + "/lines/" + line)).then().statusCode(200).body("lines", hasSize(1))
                .body("subtotalCents", equalTo(15000));
        a.owner().as().body(period(a)).post(a.path("")).then().statusCode(201).body("lines", hasSize(1))
                .body("lines[0].amountCents", equalTo(2334));
    }

    @Test
    void issuedInvoicesAreNumberedWithoutGapsNeverChangeAndAreCorrectedWithACreditNote() {
        Agency a = agency("NUM");
        long first = a.owner().as().body(Map.of("clientId", a.clientId(), "from", WEEK.toString(), "to", WEEK.plusDays(1).toString(),
                "groupBy", "PERSON")).post(a.path("")).then().statusCode(201).body("lines[0].personName", equalTo("Mats Member"))
                .extract().<Number>path("id").longValue();
        long second = a.owner().as().body(Map.of("clientId", a.clientId(), "from", WEEK.plusDays(2).toString(),
                "to", WEEK.plusDays(6).toString())).post(a.path("")).then().statusCode(201).extract().<Number>path("id").longValue();

        // Issued at the same moment, they still get 1 and 2.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> numbers;
        try {
            Future<Integer> one = pool.submit(() -> a.owner().as().post(a.path("/" + first + "/issue")).then().statusCode(200)
                    .extract().<Integer>path("number"));
            Future<Integer> two = pool.submit(() -> a.owner().as().post(a.path("/" + second + "/issue")).then().statusCode(200)
                    .extract().<Integer>path("number"));
            numbers = List.of(one.get(), two.get());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
        assertThat(numbers).containsExactlyInAnyOrder(1, 2);

        a.owner().as().body(Map.of("taxRatePercent", 5)).patch(a.path("/" + first)).then().statusCode(409).body("code", equalTo("not_draft"));
        a.owner().as().post(a.path("/" + first + "/issue")).then().statusCode(409).body("code", equalTo("not_draft"));
        a.owner().as().delete(a.path("/" + first)).then().statusCode(409).body("code", equalTo("not_draft"));
        // The project's time is on an invoice, so the project stays.
        a.owner().as().delete("/api/projects/" + a.projectId()).then().statusCode(409).body("code", equalTo("invoiced"));

        // A credit note cancels the first one with the next number, and its time can be invoiced again.
        var note = a.owner().as().post(a.path("/" + first + "/credit")).then().statusCode(201)
                .body("kind", equalTo("CREDIT_NOTE")).body("number", equalTo(3)).body("creditsInvoiceId", equalTo((int) first))
                .body("totalCents", equalTo(-2334)).body("lines[0].amountCents", equalTo(-2334)).extract();
        a.owner().as().get(a.path("/" + first)).then().statusCode(200)
                .body("creditedById", equalTo(note.<Number>path("id").intValue())).body("status", equalTo("ISSUED"));
        a.owner().as().post(a.path("/" + first + "/credit")).then().statusCode(409).body("code", equalTo("already_credited"));
        a.owner().as().post(a.path("/" + note.<Number>path("id") + "/credit")).then().statusCode(409).body("code", equalTo("not_issued"));
        a.owner().as().body(Map.of("clientId", a.clientId(), "from", WEEK.toString(), "to", WEEK.plusDays(1).toString()))
                .post(a.path("")).then().statusCode(201).body("subtotalCents", equalTo(2334));

        a.owner().as().get(a.path("")).then().statusCode(200).body("number.findAll { it != null }", containsInAnyOrder(1, 2, 3));
    }

    @Test
    void theCsvEscapesWhatPeopleTypedAndOnlyManagersSeeInvoices() {
        Agency a = agency("CSV");
        Account viewer = Api.signup("Cleo Client");
        Api.addMember(a.owner(), viewer, "CLIENT", a.clientId());
        Account stranger = Api.signup("Sid Stranger");
        long id = a.owner().as().body(period(a)).post(a.path("")).then().statusCode(201).extract().<Number>path("id").longValue();

        String csv = a.owner().as().get(a.path("/" + id + "/lines.csv")).then().statusCode(200)
                .header("Content-Disposition", containsString("invoice-draft-" + id + ".csv")).extract().asString();
        assertThat(csv).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK").contains("150.00").contains("EUR");

        a.member().as().get(a.path("")).then().statusCode(403);
        viewer.as().get(a.path("/" + id)).then().statusCode(403);
        stranger.as().get(a.path("/" + id)).then().statusCode(404);
        stranger.as().get("/api/orgs/" + stranger.orgId() + "/invoices/" + id).then().statusCode(404);
        a.owner().as().body(Map.of("clientId", a.clientId(), "from", WEEK.toString(), "to", WEEK.minusDays(1).toString()))
                .post(a.path("")).then().statusCode(400).body("code", equalTo("invalid_range"));
    }
}
