package com.projectplanner.attachment;

import com.projectplanner.common.ApiException;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The bytes of an attachment, for a link from {@link AttachmentResource#link}. No token:
 * the link's signature says who may have them and until when. Every answer says what the
 * file is and that it is not to be guessed at ({@code nosniff}), comes with a
 * {@code Content-Disposition}, and with a policy that lets nothing in it run, so a file
 * someone uploaded can never act on the app's origin.
 */
@Path("/api")
@PermitAll
@Tag(name = "Attachments")
public class AttachmentContentResource {

    private final SignedLinks links;
    private final AttachmentStore store;

    public AttachmentContentResource(SignedLinks links, AttachmentStore store) {
        this.links = links;
        this.store = store;
    }

    @GET
    @Path("/attachments/{attachmentId}/content")
    @Operation(summary = "The bytes of a file, through a link",
            description = "Takes the query of a link from POST /attachments/{attachmentId}/link and needs no token.")
    public Response content(@PathParam("attachmentId") long attachmentId, @QueryParam("user") long user,
                            @QueryParam("expires") long expires, @QueryParam("inline") boolean inline,
                            @QueryParam("signature") String signature) {
        SignedLinks.Link link = new SignedLinks.Link(attachmentId, user, expires, inline);
        if (!links.valid(link, signature)) {
            throw new ApiException(403, "link_invalid", "This link has expired or is not valid. Open the file from the task again.");
        }
        Attachment attachment = Attachment.findById(attachmentId);
        if (attachment == null || !store.exists(attachment.storageKey)) {
            throw ApiException.notFound("Attachment");
        }
        boolean showInPage = inline && AttachmentService.previewable(attachment);
        return Response.ok(store.path(attachment.storageKey).toFile())
                .type(attachment.contentType)
                .header("Content-Disposition", disposition(showInPage ? "inline" : "attachment", attachment.fileName))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Cache-Control", "private, no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    /** RFC 6266: a plain ASCII name for old clients, and the real one encoded in filename*. */
    static String disposition(String type, String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\\", "_").replace("\"", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
