package com.projectplanner.mail;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sends mail, and does the work that leads to a mail, on a thread of its own, after the
 * request has its answer. A slow or broken mail server never holds up a request, and a
 * request answers in the same time whether or not a mail comes of it.
 * <p>
 * A failure is logged without the address or the link at the usual levels: the address
 * is personal data and the link is as good as a password for as long as it works. The
 * whole exception, which can name the address, is logged at DEBUG.
 */
@ApplicationScoped
public class MailOutbox {

    private static final Logger LOG = Logger.getLogger(MailOutbox.class);
    private static final Duration SEND_TIMEOUT = Duration.ofMinutes(1);

    private final TransactionSynchronizationRegistry transactions;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mail-outbox");
        thread.setDaemon(true);
        return thread;
    });

    public MailOutbox(TransactionSynchronizationRegistry transactions) {
        this.transactions = transactions;
    }

    /** Sends the mail once the current transaction has committed, and not at all if it rolls back. */
    public void sendAfterCommit(String kind, MailTemplateInstance mail) {
        if (transactions.getTransactionStatus() != Status.STATUS_ACTIVE) {
            run(kind, () -> send(kind, mail));
            return;
        }
        transactions.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    run(kind, () -> send(kind, mail));
                }
            }
        });
    }

    /** Runs work on the mail thread. */
    public void run(String kind, Runnable work) {
        worker.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                LOG.warnf("The %s mail failed: %s", kind, e.getClass().getName());
                LOG.debugf(e, "The %s mail failed", kind);
            }
        });
    }

    /** Sends a mail on the current thread. Only for work that already runs on the mail thread. */
    public void send(String kind, MailTemplateInstance mail) {
        try {
            mail.send().await().atMost(SEND_TIMEOUT);
        } catch (RuntimeException e) {
            LOG.warnf("Could not send the %s mail: %s", kind, e.getClass().getName());
            LOG.debugf(e, "Could not send the %s mail", kind);
        }
    }

    /** Waits until everything handed over so far is done. For tests. */
    public void awaitIdle() {
        try {
            worker.submit(() -> {
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The mail thread did not finish", e);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdown();
        try {
            worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
