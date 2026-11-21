package com.projectplanner.search;

import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Organisation;
import com.projectplanner.task.TaskStatus;
import com.projectplanner.search.SearchDtos.RecentRequest;
import com.projectplanner.search.SearchDtos.SearchHit;
import com.projectplanner.search.SearchDtos.SearchResponse;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Path("/api/orgs/{orgId}/search")
@Authenticated
@Tag(name = "Search")
public class SearchResource {

    private final OrgAccess access;
    private final SearchService search;
    private final Clock clock;

    public SearchResource(OrgAccess access, SearchService search, Clock clock) {
        this.access = access;
        this.search = search;
        this.clock = clock;
    }

    @GET
    @Operation(summary = "Find projects, tasks and comments by text",
            description = "Projects by name, code or client, tasks by title or description, comments by their text. "
                    + "The text is matched as it is, wherever it occurs and without regard to case. "
                    + "Only what you can open is found: a client viewer gets hits from the projects of their client. "
                    + "With assigneeId or status only tasks are found, and q is optional: up to 200 of them, the earliest due first, "
                    + "those without a due date last, then by project and their place in it. That is the page of somebody's tasks. "
                    + "more names the kinds that had more hits than the limit.")
    public SearchResponse search(@PathParam("orgId") long orgId,
                                 @Parameter(description = "At least two characters. Anything shorter finds nothing, unless assigneeId or status is given.")
                                 @QueryParam("q") @Size(max = 100) String q,
                                 @Parameter(description = "How many hits of each kind at most, 1 to 20. With assigneeId or status, 1 to 200.")
                                 @QueryParam("limit") @DefaultValue("8") int limit,
                                 @Parameter(description = "Only the tasks of this person.")
                                 @QueryParam("assigneeId") Long assigneeId,
                                 @Parameter(description = "Only tasks with one of these statuses. May be given more than once.")
                                 @QueryParam("status") List<TaskStatus> status) {
        Membership me = access.member(orgId);
        Set<TaskStatus> statuses = status == null || status.isEmpty() ? Set.of() : EnumSet.copyOf(status);
        if (assigneeId == null && statuses.isEmpty()) {
            return search.search(me, q, limit);
        }
        LocalDate today = Organisation.<Organisation>findById(orgId).today(clock);
        return search.tasks(me, today, assigneeId, statuses, q, limit);
    }

    @POST
    @Path("/recent")
    @Operation(summary = "The hits picked before, as they are now",
            description = "The web app keeps only the kind and the id of what was picked. This gives back the current name or title "
                    + "of each, in the order sent, and leaves out what is gone or what you can no longer open.")
    public List<SearchHit> recent(@PathParam("orgId") long orgId, @Valid @NotNull RecentRequest request) {
        Membership me = access.member(orgId);
        return search.recent(me, request.picks());
    }
}
