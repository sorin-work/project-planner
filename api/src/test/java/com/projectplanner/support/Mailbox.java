package com.projectplanner.support;

import com.projectplanner.mail.MailOutbox;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads the mails the tests sent to the mock mailbox, once the mail thread is done. */
public final class Mailbox {

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    private final MockMailbox mailbox;
    private final MailOutbox outbox;

    public Mailbox(MockMailbox mailbox, MailOutbox outbox) {
        this.mailbox = mailbox;
        this.outbox = outbox;
    }

    public List<Mail> to(String address) {
        outbox.awaitIdle();
        List<Mail> mails = mailbox.getMailsSentTo(address);
        return mails == null ? List.of() : mails;
    }

    /** The one mail sent to the address. */
    public Mail only(String address) {
        List<Mail> mails = to(address);
        assertThat(mails).as("mails to %s", address).hasSize(1);
        return mails.get(0);
    }

    /** The token in the link of the last mail sent to the address. */
    public String lastToken(String address) {
        List<Mail> mails = to(address);
        assertThat(mails).as("mails to %s", address).isNotEmpty();
        return token(mails.get(mails.size() - 1));
    }

    public static String token(Mail mail) {
        Matcher matcher = TOKEN.matcher(mail.getText());
        assertThat(matcher.find()).as("a link with a token in: %s", mail.getText()).isTrue();
        return matcher.group(1);
    }

    /** The token of the first link to {@code page} in the mail, which may hold a dot. */
    public static String tokenOfLink(Mail mail, String page) {
        Matcher matcher = Pattern.compile(Pattern.quote(page) + "#token=([A-Za-z0-9_.-]+)").matcher(mail.getText());
        assertThat(matcher.find()).as("a link to %s in: %s", page, mail.getText()).isTrue();
        return matcher.group(1);
    }
}
