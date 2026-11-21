package com.projectplanner.notification;

import com.projectplanner.auth.CurrentUser;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.org.Membership;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Path("/api/me/notification-preferences")
@Authenticated
@Tag(name = "Collaboration")
public class NotificationPreferenceResource {

    private final CurrentUser currentUser;
    private final NotificationPreferences preferences;
    private final MailSettings mail;

    public NotificationPreferenceResource(CurrentUser currentUser, NotificationPreferences preferences, MailSettings mail) {
        this.currentUser = currentUser;
        this.preferences = preferences;
        this.mail = mail;
    }

    @GET
    @Operation(summary = "How you get each type of notification",
            description = "Every type, with your choice or the default. EMAIL also shows the notification in the app; it is only mailed when the server sends mail, which mailAvailable says.")
    public PreferencesResponse get() {
        return respond(currentUser.id());
    }

    @PUT
    @Transactional
    @Operation(summary = "Change how you get some types of notification",
            description = "Only the types that are sent change. The choice holds for every organisation you are in. DAILY_SUMMARY is mail only: anything but EMAIL turns it off.")
    public PreferencesResponse update(@Valid @NotNull PreferencesRequest request) {
        long userId = currentUser.id();
        request.preferences().forEach(p -> preferences.set(userId, p.type(),
                p.type().mailOnly() && p.delivery() != Delivery.EMAIL ? Delivery.OFF : p.delivery()));
        return respond(userId);
    }

    private PreferencesResponse respond(long userId) {
        Map<NotificationType, Delivery> current = preferences.of(userId);
        // The summary is on by default for those who manage; somebody who manages anywhere sees it on.
        Delivery summary = NotificationPreferences.summaryDefault(
                Membership.<Membership>list("userId", userId).stream().anyMatch(m -> m.role.canManage()));
        boolean summaryChosen = NotificationPreference.count("userId = ?1 and type = ?2", userId, NotificationType.DAILY_SUMMARY) > 0;
        List<Preference> list = Arrays.stream(NotificationType.values())
                .map(type -> type == NotificationType.DAILY_SUMMARY
                        ? new Preference(type, summaryChosen ? current.get(type) : summary, summary)
                        : new Preference(type, current.get(type), NotificationPreferences.DEFAULTS.get(type)))
                .toList();
        return new PreferencesResponse(mail.enabled(), list);
    }

    public record Preference(NotificationType type, Delivery delivery,
                             @Schema(description = "What holds for the type without a choice of yours.") Delivery byDefault) {
    }

    public record PreferenceChange(@NotNull NotificationType type, @NotNull Delivery delivery) {
    }

    public record PreferencesRequest(@NotNull @Size(max = 20) List<@NotNull @Valid PreferenceChange> preferences) {
    }

    public record PreferencesResponse(
            @Schema(description = "Whether this server sends mail at all.") boolean mailAvailable,
            List<Preference> preferences) {
    }
}
