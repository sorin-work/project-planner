package com.projectplanner.report;

import com.projectplanner.auth.User;
import com.projectplanner.calendar.OrgCalendars;
import com.projectplanner.client.Client;
import com.projectplanner.org.Membership;
import com.projectplanner.org.RateBook;
import com.projectplanner.project.Project;
import com.projectplanner.report.TimeReport.Bucket;
import com.projectplanner.report.TimeReport.Group;
import com.projectplanner.report.TimeReport.GroupBy;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Task;
import com.projectplanner.time.ApprovedWeeks;
import com.projectplanner.time.TimeEntry;
import com.projectplanner.workload.WorkloadCalculator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the time behind the reports. The database does the adding up: no query
 * here returns a row per entry, however long the range is.
 */
@ApplicationScoped
public class TimeReportService {

    /** Which time a report is about. Null means no restriction. */
    public record Filter(Long userId, Long projectId, Long clientId, Boolean billable, boolean approvedOnly) {

        public Filter(Long userId, Long projectId, Long clientId, Boolean billable) {
            this(userId, projectId, clientId, billable, false);
        }
    }

    private static final String WHERE = " where e.organisationId = :org and e.entryDate between :from and :to"
            // A running timer has no time yet.
            + " and (e.startedAt is null or e.endedAt is not null)";

    private final OrgCalendars calendars;

    public TimeReportService(OrgCalendars calendars) {
        this.calendars = calendars;
    }

    /** Time per project, client, person or task. Without {@code withMoney} every amount is zero. */
    public TimeReport.Result time(long organisationId, ReportRange range, GroupBy groupBy, Filter filter, boolean withMoney) {
        List<Bucket> buckets = buckets(organisationId, range, groupBy == GroupBy.TASK, filter,
                withMoney ? RateBook.load(organisationId) : null);
        return TimeReport.group(buckets, groups(groupBy, buckets));
    }

    /**
     * The entries of a range, grouped by everything that decides where they are counted and
     * what they cost: project, person, billable flag and length (and the task when rows are
     * per task). A year of a team's time comes back as a few hundred rows.
     */
    public List<Bucket> buckets(long organisationId, ReportRange range, boolean byTask, Filter filter, RateBook rates) {
        StringBuilder jpql = new StringBuilder("select e.projectId, e.userId, e.billable, e.minutes, count(e.id)")
                .append(byTask ? ", e.taskId" : "")
                .append(" from TimeEntry e").append(WHERE);
        Map<String, Object> params = new HashMap<>();
        if (filter.userId() != null) {
            jpql.append(" and e.userId = :user");
            params.put("user", filter.userId());
        }
        if (filter.projectId() != null) {
            jpql.append(" and e.projectId = :project");
            params.put("project", filter.projectId());
        }
        if (filter.clientId() != null) {
            jpql.append(" and e.projectId in (select p.id from Project p where p.organisationId = :org and p.clientId = :client)");
            params.put("client", filter.clientId());
        }
        if (filter.billable() != null) {
            jpql.append(" and e.billable = :billable");
            params.put("billable", filter.billable());
        }
        if (filter.approvedOnly()) {
            // The approved weeks as spans of days per person: weeks in a row are one span.
            List<ApprovedWeeks.Span> spans = ApprovedWeeks.of(organisationId, range.from(), range.to()).spans();
            if (spans.isEmpty()) {
                jpql.append(" and 1 = 0");
            } else {
                jpql.append(" and (");
                for (int i = 0; i < spans.size(); i++) {
                    jpql.append(i == 0 ? "" : " or ").append("(e.userId = :au").append(i).append(" and e.entryDate between :af")
                            .append(i).append(" and :at").append(i).append(')');
                    params.put("au" + i, spans.get(i).userId());
                    params.put("af" + i, spans.get(i).from());
                    params.put("at" + i, spans.get(i).to());
                }
                jpql.append(')');
            }
        }
        jpql.append(" group by e.projectId, e.userId, e.billable, e.minutes").append(byTask ? ", e.taskId" : "");
        TypedQuery<Object[]> query = TimeEntry.getEntityManager().createQuery(jpql.toString(), Object[].class)
                .setParameter("org", organisationId).setParameter("from", range.from()).setParameter("to", range.to());
        params.forEach(query::setParameter);
        List<Object[]> rows = query.getResultList();

        Map<Long, BigDecimal> projectRates = new HashMap<>();
        if (rates != null) {
            byId(Project.class, rows.stream().map(r -> (Long) r[0]).collect(Collectors.toSet()))
                    .forEach(p -> projectRates.put(p.id, p.hourlyRate));
        }
        List<Bucket> buckets = new ArrayList<>();
        for (Object[] r : rows) {
            long projectId = (Long) r[0];
            long userId = (Long) r[1];
            buckets.add(new Bucket(projectId, userId, byTask ? (Long) r[5] : null, (Boolean) r[2], ((Number) r[3]).intValue(),
                    ((Number) r[4]).longValue(), rates == null ? null : rates.rate(projectRates.get(projectId), userId)));
        }
        return buckets;
    }

    /** The row each bucket goes into, with the names looked up once for all of them. */
    private static Function<Bucket, Group> groups(GroupBy groupBy, List<Bucket> buckets) {
        if (groupBy == GroupBy.MEMBER) {
            Map<Long, String> names = new HashMap<>();
            byId(User.class, ids(buckets, Bucket::userId)).forEach(u -> names.put(u.id, u.name));
            return b -> new Group(String.valueOf(b.userId()), names.get(b.userId()));
        }
        Map<Long, Project> projects = new HashMap<>();
        byId(Project.class, ids(buckets, Bucket::projectId)).forEach(p -> projects.put(p.id, p));
        Function<Bucket, String> project = b -> projects.get(b.projectId()).code + " · " + projects.get(b.projectId()).name;
        if (groupBy == GroupBy.PROJECT) {
            return b -> new Group(String.valueOf(b.projectId()), project.apply(b));
        }
        if (groupBy == GroupBy.CLIENT) {
            Map<Long, String> clients = new HashMap<>();
            byId(Client.class, projects.values().stream().map(p -> p.clientId).filter(Objects::nonNull).collect(Collectors.toSet()))
                    .forEach(c -> clients.put(c.id, c.name));
            return b -> {
                Long clientId = projects.get(b.projectId()).clientId;
                return clientId == null ? new Group("none", "Internal") : new Group(String.valueOf(clientId), clients.get(clientId));
            };
        }
        Map<Long, String> tasks = new HashMap<>();
        byId(Task.class, buckets.stream().map(Bucket::taskId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(t -> tasks.put(t.id, t.title));
        // Time without a task is one row per project.
        return b -> b.taskId() == null
                ? new Group("project-" + b.projectId(), project.apply(b) + " · No task")
                : new Group(String.valueOf(b.taskId()), project.apply(b) + " · " + tasks.get(b.taskId()));
    }

    private static Set<Long> ids(List<Bucket> buckets, Function<Bucket, Long> id) {
        return buckets.stream().map(id).collect(Collectors.toSet());
    }

    /** The rows with these ids in one query, and no query at all for no ids. */
    private static <T> List<T> byId(Class<T> entity, Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return TimeEntry.getEntityManager().createQuery("from " + entity.getSimpleName() + " where id in :ids", entity)
                .setParameter("ids", ids).getResultList();
    }

    /**
     * Logged and billable time of every team member against their capacity. Both are counted
     * from the first day of the range to its last day or {@code today}, whichever comes
     * first. Time can be logged a week ahead, and days that have not happened yet have no
     * capacity to hold it against: a week filled in on its Monday would read five hundred percent.
     */
    public UtilisationReport utilisation(long organisationId, ReportRange range, LocalDate today) {
        LocalDate countedTo = range.to().isAfter(today) ? today : range.to();
        boolean begun = !countedTo.isBefore(range.from());
        WorkCalendar calendar = calendars.of(organisationId);
        WorkloadCalculator workload = new WorkloadCalculator(calendar);
        int workingDays = calendar.workingDaysBetween(range.from(), countedTo);
        Map<Long, Set<LocalDate>> daysOff = begun ? calendars.daysOff(organisationId, range.from(), countedTo) : Map.of();

        Map<Long, long[]> time = new HashMap<>();
        if (begun) {
            TimeEntry.getEntityManager().createQuery("select e.userId, e.billable, sum(e.minutes) from TimeEntry e" + WHERE
                            + " group by e.userId, e.billable", Object[].class)
                    .setParameter("org", organisationId).setParameter("from", range.from()).setParameter("to", countedTo)
                    .getResultList()
                    .forEach(r -> {
                        long[] sums = time.computeIfAbsent((Long) r[0], k -> new long[2]);
                        long minutes = ((Number) r[2]).longValue();
                        sums[0] += minutes;
                        sums[1] += (Boolean) r[1] ? minutes : 0;
                    });
        }

        List<Membership> team = Membership.forOrganisation(organisationId).stream().filter(m -> m.role.isTeam()).toList();
        Map<Long, String> names = new HashMap<>();
        byId(User.class, team.stream().map(m -> m.userId).collect(Collectors.toSet())).forEach(u -> names.put(u.id, u.name));
        List<Utilisation.Person> people = team.stream().map(m -> {
            long[] sums = time.getOrDefault(m.userId, new long[2]);
            long capacity = workload.capacityMinutes(new WorkloadCalculator.Person(m.userId, m.weeklyCapacityHours.doubleValue(),
                            daysOff.getOrDefault(m.userId, Set.of())),
                    range.from(), countedTo);
            return new Utilisation.Person(m.userId, names.get(m.userId), sums[0], sums[1], capacity);
        }).toList();
        return new UtilisationReport(begun ? countedTo : null, workingDays, Utilisation.of(people));
    }

    /** {@code countedTo} is the last day time and capacity were counted for. Null when the whole range lies in the future. */
    public record UtilisationReport(LocalDate countedTo, int workingDays, Utilisation.Result result) {
    }
}
