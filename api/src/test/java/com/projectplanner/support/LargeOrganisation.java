package com.projectplanner.support;

import com.projectplanner.project.Project;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api.Account;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * An organisation the size the app has to cope with, for the tests that count statements:
 * fifty projects, half of them for clients, one of them with a thousand tasks and twenty
 * thousand time entries, five people and a queue of submitted weeks. The people come through
 * the API, the rest is written in a few statements of SQL, since a thousand requests would
 * take longer than the tests themselves. Built once per test run.
 */
public final class LargeOrganisation {

    public static final int PROJECTS = 50;
    public static final int BIG_PROJECT_TASKS = 1_000;
    public static final int BIG_PROJECT_ENTRIES = 20_000;
    public static final int SUBMITTED_WEEKS = 12;

    public record Org(Account owner, Account manager, List<Account> members, Account clientViewer, long bigProjectId, long bigProjectTaskId) {
    }

    private static Org built;

    private LargeOrganisation() {
    }

    public static synchronized Org get() {
        if (built == null) {
            built = build();
        }
        return built;
    }

    private static Org build() {
        Account owner = Api.signup("Olga Large");
        Account manager = Api.signup("Milo Large");
        List<Account> members = List.of(Api.signup("Ada Large"), Api.signup("Ben Large"), Api.signup("Cy Large"));
        Account clientViewer = Api.signup("Vi Large");
        long client = Api.createClient(owner, "Large client");
        Api.addMember(owner, manager, "MANAGER");
        members.forEach(m -> Api.addMember(owner, m, "MEMBER"));
        Api.addMember(owner, clientViewer, "CLIENT", client);
        // The owner is in a few other organisations too, which /me lists.
        for (int i = 0; i < 4; i++) {
            Api.addMember(Api.signup("Other owner " + i), owner, "MEMBER");
        }

        long org = owner.orgId();
        List<Long> team = List.of(owner.userId(), manager.userId(), members.get(0).userId(), members.get(1).userId(), members.get(2).userId());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QuarkusTransaction.requiringNew().run(() -> {
            EntityManager em = Project.getEntityManager();
            em.createNativeQuery("""
                    insert into projects (organisation_id, client_id, name, code, status, start_date, end_date,
                                          budget_hours, budget_amount, created_at, updated_at)
                    select :org, case when mod(x, 2) = 0 then :client end, 'Large project ' || x, 'LP' || x,
                           case when mod(x, 5) = 4 then 'DONE' when mod(x, 7) = 6 then 'PLANNED' else 'ACTIVE' end,
                           dateadd('DAY', -200, current_date), dateadd('DAY', 100, current_date),
                           case when mod(x, 3) <> 0 then 2000 end, case when mod(x, 3) = 1 then 150000 end,
                           current_timestamp, current_timestamp
                    from system_range(1, :n) as r (x)""")
                    .setParameter("org", org).setParameter("client", client).setParameter("n", PROJECTS).executeUpdate();
            long first = ((Number) em.createNativeQuery("select min(id) from projects where organisation_id = :org")
                    .setParameter("org", org).getSingleResult()).longValue();
            // A thousand tasks on the first project and five on each of the others.
            em.createNativeQuery("""
                    insert into tasks (project_id, title, status, priority, estimate_hours, start_date, due_date,
                                       assignee_id, created_at, updated_at)
                    select p.id, 'Task ' || x,
                           case when mod(x, 4) = 0 then 'DONE' when mod(x, 4) = 1 then 'IN_PROGRESS' else 'TODO' end,
                           'MEDIUM', 4 + mod(x, 5), dateadd('DAY', mod(x, 150) - 100, current_date),
                           dateadd('DAY', mod(x, 150) - 95, current_date), :assignee, current_timestamp, current_timestamp
                    from projects p join system_range(1, :tasks) as r (x) on p.id = :first or x <= 5
                    where p.organisation_id = :org""")
                    .setParameter("org", org).setParameter("first", first).setParameter("tasks", BIG_PROJECT_TASKS)
                    .setParameter("assignee", owner.userId()).executeUpdate();
            // Twenty thousand entries on the first project over the last three hundred days, by five people,
            // of eight lengths, and twenty on each of the others.
            em.createNativeQuery("""
                    insert into time_entries (organisation_id, user_id, project_id, entry_date, minutes, billable, created_at)
                    select :org, case mod(x, 5) when 0 then :u0 when 1 then :u1 when 2 then :u2 when 3 then :u3 else :u4 end,
                           p.id, dateadd('DAY', -mod(x, 300), current_date), 15 + 15 * mod(x, 8), mod(x, 3) <> 0,
                           current_timestamp
                    from projects p join system_range(1, :entries) as r (x) on p.id = :first or x <= 20
                    where p.organisation_id = :org""")
                    .setParameter("org", org).setParameter("first", first).setParameter("entries", BIG_PROJECT_ENTRIES)
                    .setParameter("u0", team.get(0)).setParameter("u1", team.get(1)).setParameter("u2", team.get(2))
                    .setParameter("u3", team.get(3)).setParameter("u4", team.get(4)).executeUpdate();
            // Twelve weeks of the three members waiting for review.
            LocalDate week = WorkCalendar.weekStart(today).minusWeeks(1);
            for (int i = 0; i < SUBMITTED_WEEKS; i++) {
                for (Account member : members) {
                    em.createNativeQuery("insert into timesheets (organisation_id, user_id, week_start, status, submitted_at)"
                                    + " values (:org, :user, :week, 'SUBMITTED', current_timestamp)")
                            .setParameter("org", org).setParameter("user", member.userId()).setParameter("week", week.minusWeeks(i))
                            .executeUpdate();
                }
            }
        });
        Number bigProject = owner.as().get("/api/orgs/" + org + "/projects/options").then().statusCode(200)
                .extract().path("find { it.code == 'LP1' }.id");
        Number task = owner.as().get("/api/projects/" + bigProject + "/tasks").then().statusCode(200).extract().path("[0].id");
        return new Org(owner, manager, members, clientViewer, bigProject.longValue(), task.longValue());
    }
}
