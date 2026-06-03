package com.projectplanner.client;

import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Path("/api/orgs/{orgId}/clients")
@Authenticated
public class ClientResource {

    private final OrgAccess access;
    private final Clock clock;

    public ClientResource(OrgAccess access, Clock clock) {
        this.access = access;
        this.clock = clock;
    }

    @GET
    public List<ClientResponse> list(@PathParam("orgId") long orgId,
                                     @QueryParam("archived") @DefaultValue("false") boolean includeArchived) {
        Membership me = access.member(orgId);
        if (me.role == Role.CLIENT) {
            Client own = me.clientId == null ? null : Client.findById(me.clientId);
            return own == null ? List.of() : List.of(ClientResponse.of(own));
        }
        String query = includeArchived ? "organisationId = ?1 order by name" : "organisationId = ?1 and archived = false order by name";
        return Client.<Client>list(query, orgId).stream().map(ClientResponse::of).toList();
    }

    @GET
    @Path("/{clientId}")
    public ClientResponse get(@PathParam("orgId") long orgId, @PathParam("clientId") long clientId) {
        Membership me = access.member(orgId);
        if (me.role == Role.CLIENT && !Objects.equals(me.clientId, clientId)) {
            throw ApiException.notFound("Client");
        }
        return ClientResponse.of(find(orgId, clientId));
    }

    @POST
    @Transactional
    @ResponseStatus(201)
    public ClientResponse create(@PathParam("orgId") long orgId, @Valid @NotNull ClientRequest request) {
        access.manager(orgId);
        Client client = new Client();
        client.organisationId = orgId;
        client.createdAt = Instant.now(clock);
        apply(client, request);
        client.persist();
        return ClientResponse.of(client);
    }

    @PUT
    @Path("/{clientId}")
    @Transactional
    public ClientResponse update(@PathParam("orgId") long orgId, @PathParam("clientId") long clientId,
                                 @Valid @NotNull ClientRequest request) {
        access.manager(orgId);
        Client client = find(orgId, clientId);
        apply(client, request);
        return ClientResponse.of(client);
    }

    /** Clients with projects are archived instead of deleted so the history stays intact. */
    @DELETE
    @Path("/{clientId}")
    @Transactional
    @ResponseStatus(204)
    public void delete(@PathParam("orgId") long orgId, @PathParam("clientId") long clientId) {
        access.manager(orgId);
        Client client = find(orgId, clientId);
        if (Project.count("clientId", clientId) > 0) {
            client.archived = true;
        } else {
            client.delete();
        }
    }

    private static void apply(Client client, ClientRequest request) {
        client.name = request.name().trim();
        client.contactName = blankToNull(request.contactName());
        client.contactEmail = blankToNull(request.contactEmail());
        client.notes = blankToNull(request.notes());
        client.archived = request.archived();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Client find(long orgId, long clientId) {
        Client client = Client.findById(clientId);
        if (client == null || !Objects.equals(client.organisationId, orgId)) {
            throw ApiException.notFound("Client");
        }
        return client;
    }

    public record ClientRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 120) String contactName,
            @Email @Size(max = 320) String contactEmail,
            @Size(max = 2000) String notes,
            boolean archived) {
    }

    public record ClientResponse(long id, String name, String contactName, String contactEmail, String notes,
                                 boolean archived, long projectCount) {
        static ClientResponse of(Client c) {
            return new ClientResponse(c.id, c.name, c.contactName, c.contactEmail, c.notes, c.archived,
                    Project.count("clientId", c.id));
        }
    }
}
