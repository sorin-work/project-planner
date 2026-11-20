package com.projectplanner.search;

import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.search.SearchDtos.SearchResponse;
import io.quarkus.security.Authenticated;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/orgs/{orgId}/search")
@Authenticated
@Tag(name = "Search")
public class SearchResource {

    private final OrgAccess access;
    private final SearchService search;

    public SearchResource(OrgAccess access, SearchService search) {
        this.access = access;
        this.search = search;
    }

    @GET
    @Operation(summary = "Find projects, tasks and comments by text",
            description = "Projects by name, code or client, tasks by title or description, comments by their text. "
                    + "The text is matched as it is, wherever it occurs and without regard to case. "
                    + "Only what you can open is found: a client viewer gets hits from the projects of their client.")
    public SearchResponse search(@PathParam("orgId") long orgId,
                                 @Parameter(description = "At least two characters. Anything shorter finds nothing.")
                                 @QueryParam("q") @Size(max = 100) String q,
                                 @Parameter(description = "How many hits of each kind at most, 1 to 20.")
                                 @QueryParam("limit") @DefaultValue("8") int limit) {
        Membership me = access.member(orgId);
        return search.search(me, q, limit);
    }
}
