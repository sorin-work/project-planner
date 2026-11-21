package com.projectplanner.search;

import com.projectplanner.client.Client;
import com.projectplanner.comment.Comment;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.search.SearchDtos.RecentPick;
import com.projectplanner.search.SearchDtos.SearchHitType;
import com.projectplanner.search.SearchDtos.SearchHit;
import com.projectplanner.search.SearchDtos.SearchResponse;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds projects, tasks and comments by text. Who may see what is part of every query:
 * the projects of the organisation, and for a client viewer only those of their client.
 * Tasks and comments are reached through those projects and through nothing else, so a
 * hit, or the number of hits, never tells of something the caller cannot open.
 */
@ApplicationScoped
public class SearchService {

    static final int MIN_LENGTH = 2;
    static final int MAX_LIMIT = 20;
    static final int MAX_TASK_LIMIT = 200;

    private static final String LIKE = " like :contains escape '" + SearchText.ESCAPE + "'";
    private static final String STARTS = " like :starts escape '" + SearchText.ESCAPE + "'";

    public SearchResponse search(Membership me, String text, int limit) {
        String query = text == null ? "" : text.strip();
        String needle = SearchText.normalise(query);
        // A client viewer who belongs to no client sees no project, so there is nothing to find.
        if (needle.length() < MIN_LENGTH || (me.role == Role.CLIENT && me.clientId == null)) {
            return SearchResponse.nothing(query);
        }
        int max = Math.max(1, Math.min(MAX_LIMIT, limit));
        // One row more than the limit says whether there is more than what is listed.
        String visible = "p.organisationId = :org" + (me.role == Role.CLIENT ? " and p.clientId = :client" : "");

        List<Project> projects = Project.<Project>find("from Project p where " + visible
                        + " and (lower(p.name)" + LIKE + " or lower(p.code)" + LIKE
                        + " or p.clientId in (select c.id from Client c where c.organisationId = :org and lower(c.name)" + LIKE + "))"
                        + " order by case when lower(p.name)" + STARTS + " or lower(p.code)" + STARTS + " then 0 else 1 end, lower(p.name), p.id",
                scope(me, needle).and("starts", SearchText.startsWith(needle))).page(0, max + 1).list();

        List<Task> tasks = Task.<Task>find("from Task t where t.projectId in (select p.id from Project p where " + visible + ")"
                        + " and (lower(t.title)" + LIKE + " or lower(t.description)" + LIKE + ")"
                        // The start of the title, then the rest of the title, then the description. Open before done.
                        + " order by case when lower(t.title)" + STARTS + " then 0 when lower(t.title)" + LIKE + " then 1 else 2 end,"
                        + " case when t.status = :done then 1 else 0 end, lower(t.title), t.id",
                scope(me, needle).and("starts", SearchText.startsWith(needle)).and("done", TaskStatus.DONE)).page(0, max + 1).list();

        List<Comment> comments = Comment.<Comment>find("from Comment c where c.taskId in (select t.id from Task t where t.projectId in"
                        + " (select p.id from Project p where " + visible + ")) and lower(c.body)" + LIKE
                        + " order by c.createdAt desc, c.id desc",
                scope(me, needle)).page(0, max + 1).list();

        List<SearchHitType> more = new ArrayList<>();
        projects = cut(projects, max, SearchHitType.PROJECT, more);
        tasks = cut(tasks, max, SearchHitType.TASK, more);
        comments = cut(comments, max, SearchHitType.COMMENT, more);

        // What is looked up from here on belongs to rows that the queries above let through.
        Map<Long, Task> commented = byId(Task.list("id in ?1", ids(comments.stream().map(c -> c.taskId))), (Task t) -> t.id);
        Map<Long, Project> owners = byId(Project.list("id in ?1",
                ids(Stream.concat(tasks.stream(), commented.values().stream()).map(t -> t.projectId))), (Project p) -> p.id);
        Map<Long, Client> clients = byId(Client.list("id in ?1", ids(projects.stream().map(p -> p.clientId))), (Client c) -> c.id);

        return new SearchResponse(query,
                projects.stream().map(p -> SearchHit.project(p, p.clientId == null ? null : clients.get(p.clientId).name)).toList(),
                tasks.stream().map(t -> SearchHit.task(t, owners.get(t.projectId).code,
                        SearchText.normalise(t.title).contains(needle) ? null : SearchText.snippet(t.description, needle))).toList(),
                comments.stream().map(c -> {
                    Task task = commented.get(c.taskId);
                    return SearchHit.comment(c, task, owners.get(task.projectId).code, SearchText.snippet(c.body, needle));
                }).toList(),
                more, null);
    }

    /**
     * Only tasks, by assignee, by status or both, and by text when there is any: what a page of
     * somebody's tasks needs. Without an assignee and a status this is not asked for (see the
     * resource). Due first, the ones without a date last, then by project and their place in
     * it. The same rule as {@link #search} decides what is visible: tasks of the
     * organisation's projects, and for a client viewer of their client's.
     */
    public SearchResponse tasks(Membership me, LocalDate today, Long assigneeId, Set<TaskStatus> statuses, String text, int limit) {
        String query = text == null ? "" : text.strip();
        if (me.role == Role.CLIENT && me.clientId == null) {
            return new SearchResponse(query, List.of(), List.of(), List.of(), List.of(), today);
        }
        int max = Math.max(1, Math.min(MAX_TASK_LIMIT, limit));
        String needle = SearchText.normalise(query);
        StringBuilder hql = new StringBuilder("select t from Task t, Project p where p.id = t.projectId and p.organisationId = :org");
        if (me.role == Role.CLIENT) {
            hql.append(" and p.clientId = :client");
        }
        if (assigneeId != null) {
            hql.append(" and t.assigneeId = :assignee");
        }
        if (!statuses.isEmpty()) {
            hql.append(" and t.status in :statuses");
        }
        if (!needle.isEmpty()) {
            hql.append(" and (lower(t.title)").append(LIKE).append(" or lower(t.description)").append(LIKE).append(")");
        }
        hql.append(" order by t.dueDate asc nulls last, lower(p.code), t.position, t.id");
        TypedQuery<Task> typed = Panache.getEntityManager().createQuery(hql.toString(), Task.class)
                .setParameter("org", me.organisationId).setMaxResults(max + 1);
        if (me.role == Role.CLIENT) {
            typed.setParameter("client", me.clientId);
        }
        if (assigneeId != null) {
            typed.setParameter("assignee", assigneeId);
        }
        if (!statuses.isEmpty()) {
            typed.setParameter("statuses", statuses);
        }
        if (!needle.isEmpty()) {
            typed.setParameter("contains", SearchText.contains(needle));
        }
        List<SearchHitType> more = new ArrayList<>();
        List<Task> tasks = cut(typed.getResultList(), max, SearchHitType.TASK, more);
        Map<Long, Project> owners = byId(Project.list("id in ?1", ids(tasks.stream().map(t -> t.projectId))), (Project p) -> p.id);
        return new SearchResponse(query, List.of(),
                tasks.stream().map(t -> SearchHit.task(t, owners.get(t.projectId).code, null)).toList(),
                List.of(), more, today);
    }

    /** The first {@code max} rows, noting the kind in {@code more} when there were more. */
    private static <T> List<T> cut(List<T> rows, int max, SearchHitType type, List<SearchHitType> more) {
        if (rows.size() <= max) {
            return rows;
        }
        more.add(type);
        return rows.subList(0, max);
    }

    /**
     * The recent picks the web app kept, by kind and id, as they are now: with today's
     * names and titles, and without those that are gone or that the caller can no longer
     * open. The same rules as {@link #search} decide what is visible, in the queries.
     */
    public List<SearchHit> recent(Membership me, List<RecentPick> picks) {
        if (picks.isEmpty() || (me.role == Role.CLIENT && me.clientId == null)) {
            return List.of();
        }
        String visible = "p.organisationId = :org" + (me.role == Role.CLIENT ? " and p.clientId = :client" : "");
        Map<Long, Project> projects = byId(find(Project.class, "from Project p where " + visible + " and p.id in :ids",
                me, ids(picks, SearchHitType.PROJECT)), (Project p) -> p.id);
        Map<Long, Task> tasks = byId(find(Task.class, "from Task t where t.id in :ids"
                + " and t.projectId in (select p.id from Project p where " + visible + ")",
                me, ids(picks, SearchHitType.TASK)), (Task t) -> t.id);
        Map<Long, Comment> comments = byId(find(Comment.class, "from Comment c where c.id in :ids"
                + " and c.taskId in (select t.id from Task t where t.projectId in (select p.id from Project p where " + visible + "))",
                me, ids(picks, SearchHitType.COMMENT)), (Comment c) -> c.id);

        // As in search: what is looked up from here on belongs to rows that were let through.
        Map<Long, Task> commented = byId(Task.list("id in ?1", ids(comments.values().stream().map(c -> c.taskId))), (Task t) -> t.id);
        Map<Long, Project> owners = byId(Project.list("id in ?1", ids(Stream.concat(projects.values().stream().map(p -> p.id),
                Stream.concat(tasks.values().stream(), commented.values().stream()).map(t -> t.projectId)))), (Project p) -> p.id);
        Map<Long, Client> clients = byId(Client.list("id in ?1", ids(projects.values().stream().map(p -> p.clientId))), (Client c) -> c.id);

        List<SearchHit> hits = new ArrayList<>();
        for (RecentPick pick : picks) {
            switch (pick.type()) {
                case PROJECT -> {
                    Project p = projects.get(pick.id());
                    if (p != null) {
                        hits.add(SearchHit.project(p, p.clientId == null ? null : clients.get(p.clientId).name));
                    }
                }
                case TASK -> {
                    Task t = tasks.get(pick.id());
                    if (t != null) {
                        hits.add(SearchHit.task(t, owners.get(t.projectId).code, null));
                    }
                }
                case COMMENT -> {
                    Comment c = comments.get(pick.id());
                    if (c != null) {
                        Task task = commented.get(c.taskId);
                        hits.add(SearchHit.comment(c, task, owners.get(task.projectId).code, null));
                    }
                }
            }
        }
        return hits;
    }

    /** One query of {@link #recent}, which is not made at all without ids. */
    private static <T> List<T> find(Class<T> type, String query, Membership me, Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        TypedQuery<T> typed = Panache.getEntityManager().createQuery(query, type)
                .setParameter("org", me.organisationId).setParameter("ids", ids);
        if (me.role == Role.CLIENT) {
            typed.setParameter("client", me.clientId);
        }
        return typed.getResultList();
    }

    private static Set<Long> ids(List<RecentPick> picks, SearchHitType type) {
        return ids(picks.stream().filter(pick -> pick.type() == type).map(RecentPick::id));
    }

    /** The parameters every query takes: whose projects, and the text. */
    private static Parameters scope(Membership me, String needle) {
        Parameters parameters = Parameters.with("org", me.organisationId).and("contains", SearchText.contains(needle));
        return me.role == Role.CLIENT ? parameters.and("client", me.clientId) : parameters;
    }

    private static Set<Long> ids(Stream<Long> ids) {
        return ids.filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
        Map<Long, T> map = new HashMap<>();
        rows.forEach(row -> map.put(id.apply(row), row));
        return map;
    }
}
