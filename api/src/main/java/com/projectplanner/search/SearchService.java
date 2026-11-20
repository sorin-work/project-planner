package com.projectplanner.search;

import com.projectplanner.client.Client;
import com.projectplanner.comment.Comment;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.search.SearchDtos.SearchHitType;
import com.projectplanner.search.SearchDtos.SearchHit;
import com.projectplanner.search.SearchDtos.SearchResponse;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskStatus;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;

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
        String visible = "p.organisationId = :org" + (me.role == Role.CLIENT ? " and p.clientId = :client" : "");

        List<Project> projects = Project.<Project>find("from Project p where " + visible
                        + " and (lower(p.name)" + LIKE + " or lower(p.code)" + LIKE
                        + " or p.clientId in (select c.id from Client c where c.organisationId = :org and lower(c.name)" + LIKE + "))"
                        + " order by case when lower(p.name)" + STARTS + " or lower(p.code)" + STARTS + " then 0 else 1 end, lower(p.name), p.id",
                scope(me, needle).and("starts", SearchText.startsWith(needle))).page(0, max).list();

        List<Task> tasks = Task.<Task>find("from Task t where t.projectId in (select p.id from Project p where " + visible + ")"
                        + " and (lower(t.title)" + LIKE + " or lower(t.description)" + LIKE + ")"
                        // The start of the title, then the rest of the title, then the description. Open before done.
                        + " order by case when lower(t.title)" + STARTS + " then 0 when lower(t.title)" + LIKE + " then 1 else 2 end,"
                        + " case when t.status = :done then 1 else 0 end, lower(t.title), t.id",
                scope(me, needle).and("starts", SearchText.startsWith(needle)).and("done", TaskStatus.DONE)).page(0, max).list();

        List<Comment> comments = Comment.<Comment>find("from Comment c where c.taskId in (select t.id from Task t where t.projectId in"
                        + " (select p.id from Project p where " + visible + ")) and lower(c.body)" + LIKE
                        + " order by c.createdAt desc, c.id desc",
                scope(me, needle)).page(0, max).list();

        // What is looked up from here on belongs to rows that the queries above let through.
        Map<Long, Task> commented = byId(Task.list("id in ?1", ids(comments.stream().map(c -> c.taskId))), (Task t) -> t.id);
        Map<Long, Project> owners = byId(Project.list("id in ?1",
                ids(Stream.concat(tasks.stream(), commented.values().stream()).map(t -> t.projectId))), (Project p) -> p.id);
        Map<Long, Client> clients = byId(Client.list("id in ?1", ids(projects.stream().map(p -> p.clientId))), (Client c) -> c.id);

        return new SearchResponse(query,
                projects.stream().map(p -> new SearchHit(SearchHitType.PROJECT, p.id, p.id, p.code, null, p.name,
                        p.clientId == null ? null : clients.get(p.clientId).name)).toList(),
                tasks.stream().map(t -> new SearchHit(SearchHitType.TASK, t.id, t.projectId, owners.get(t.projectId).code, t.id, t.title,
                        SearchText.normalise(t.title).contains(needle) ? null : SearchText.snippet(t.description, needle))).toList(),
                comments.stream().map(c -> {
                    Task task = commented.get(c.taskId);
                    return new SearchHit(SearchHitType.COMMENT, c.id, task.projectId, owners.get(task.projectId).code, task.id, task.title,
                            SearchText.snippet(c.body, needle));
                }).toList());
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
