package com.projectplanner.mail;

import com.projectplanner.common.ApiException;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Optional;

/**
 * Whether the app sends mail, and the two values every mail needs. Mail is on when
 * MAIL_HOST is set. Then MAIL_FROM and APP_URL have to be set too, or the API does not
 * start: a link in a mail is built from APP_URL and never from the address a request
 * came in on, which anyone can put in a Host header.
 */
@Startup
@ApplicationScoped
public class MailSettings {

    private final boolean enabled;
    private final String from;
    private final String appUrl;
    private final boolean inviteLinkRequired;

    public MailSettings(@ConfigProperty(name = "planner.mail.host") Optional<String> host,
                        @ConfigProperty(name = "planner.mail.from") Optional<String> from,
                        @ConfigProperty(name = "planner.app-url") Optional<String> appUrl,
                        @ConfigProperty(name = "planner.signup.invite-link-required") boolean inviteLinkRequired) {
        this.enabled = host.filter(h -> !h.isBlank()).isPresent();
        if (enabled) {
            this.from = from.filter(f -> !f.isBlank())
                    .orElseThrow(() -> new IllegalStateException("MAIL_HOST is set, so MAIL_FROM has to be set too"));
            this.appUrl = checkedAppUrl(appUrl.orElse(""));
        } else {
            this.from = null;
            this.appUrl = null;
        }
        this.inviteLinkRequired = inviteLinkRequired;
    }

    static String checkedAppUrl(String value) {
        String url = value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "MAIL_HOST is set, so APP_URL has to be the address of the web app, such as https://planner.example.com");
        }
        return url;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Only when mail is on: whether joining at sign-up needs the link from the invitation mail. */
    public boolean inviteLinkRequired() {
        return enabled && inviteLinkRequired;
    }

    public String from() {
        return from;
    }

    /** A link into the web app, with the token after the #, which browsers never send. */
    public String link(String page, String token) {
        return appUrl + page + "#token=" + token;
    }

    public String link(String page) {
        return appUrl + page;
    }

    /** For the endpoints that only make sense when the app can send mail. */
    public void requireEnabled() {
        if (!enabled) {
            throw new ApiException(404, "mail_off", "This server does not send mail");
        }
    }
}
