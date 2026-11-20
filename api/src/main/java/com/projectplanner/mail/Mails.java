package com.projectplanner.mail;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The mails the app sends. Each one is fixed wording, a link and how long the link
 * works. Nothing in them is text a user typed, not even a name: a mail that carries
 * somebody's words in the app's name is a gift to anyone who wants to send phishing.
 * The templates are in {@code templates/Mails}, as plain text and as simple HTML.
 */
@ApplicationScoped
public class Mails {

    static final String SUBJECT_RESET = "Reset your project-planner password";
    static final String SUBJECT_INVITATION = "You are invited to project-planner";
    static final String SUBJECT_ADDED = "You were added to an organisation on project-planner";

    @CheckedTemplate
    static class Templates {
        static native MailTemplateInstance passwordReset(String link, String lifetime);

        static native MailTemplateInstance invitation(String link, String lifetime);

        static native MailTemplateInstance addedToOrganisation(String link);
    }

    private final MailSettings settings;

    public Mails(MailSettings settings) {
        this.settings = settings;
    }

    public MailTemplateInstance passwordReset(String to, String token, String lifetime) {
        return Templates.passwordReset(settings.link("/reset", token), lifetime)
                .from(settings.from()).to(to).subject(SUBJECT_RESET);
    }

    public MailTemplateInstance invitation(String to, String token, String lifetime) {
        return Templates.invitation(settings.link("/invite", token), lifetime)
                .from(settings.from()).to(to).subject(SUBJECT_INVITATION);
    }

    public MailTemplateInstance addedToOrganisation(String to) {
        return Templates.addedToOrganisation(settings.link("/login"))
                .from(settings.from()).to(to).subject(SUBJECT_ADDED);
    }
}
