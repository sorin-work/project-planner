package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brakes on guessing passwords.
 * <p>
 * The one that refuses is per account and client address: a few wrong passwords for an
 * account from one address pass, then each further try from that address for that account
 * waits, counted from the last wrong one: a second, then two, four and so on, up to
 * {@link #MAX_WAIT}. A try during the wait is refused with {@code 429 too_many_attempts}
 * before the password is looked at, so that the answer says nothing about it, and it does
 * not count: the wait never grows from trying again. Somebody trying passwords from one
 * place only slows down that place. The owner signing in from another address is never
 * held back by it, and a password reset clears it everywhere.
 * <p>
 * Two more counts never refuse anything, so that neither can keep anybody with the right
 * password out: wrong passwords for the account from every address together, and wrong
 * passwords from one address for every account. Past {@link #ACCOUNT_FREE} or
 * {@link #ADDRESS_FREE} of them, a new pair of account and address gets a single free try
 * instead of {@link #PAIR_FREE} before it waits. That slows down guessing from many
 * addresses at one account, and from one address at many accounts, while an office behind
 * one address, where somebody typed a wrong password, still signs in.
 * <p>
 * A try is counted as a failure of its pair before the password is checked, and taken back
 * when it was right. The count is moved on by an update that only matches the count it
 * read, so that of a burst of tries at the same moment only as many get through as the
 * limit lets through.
 * <p>
 * The pairs and the counts per account are in the database, so that they hold across a
 * restart and across copies of the API. The counts per address are in memory, like
 * {@link com.projectplanner.mail.ClientThrottle}, and a restart resets them. All of them
 * forget the failures after a quiet spell, so that a typo next month does not wait for one
 * from last year.
 */
@ApplicationScoped
public class LoginLimits {

    static final int PAIR_FREE = 5;
    static final int PRESSED_FREE = 1;
    static final int ACCOUNT_FREE = 20;
    static final int ADDRESS_FREE = 100;
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    static final Duration MAX_WAIT = Duration.ofMinutes(5);
    static final Duration ACCOUNT_QUIET = Duration.ofDays(1);
    static final Duration ADDRESS_QUIET = Duration.ofHours(1);
    static final int MAX_CLIENTS = 10_000;
    private static final int CLAIM_ATTEMPTS = 3;

    /** The failures of one address and when the last one was. */
    record Tally(int failures, Instant last) {
    }

    private final Map<String, Tally> addresses;

    public LoginLimits() {
        this(MAX_CLIENTS);
    }

    LoginLimits(int maxClients) {
        // In order of use, so that the eldest entry is the address that tried least recently.
        this.addresses = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tally> eldest) {
                return size() > maxClients;
            }
        };
    }

    /** How long a try waits after {@code failures} wrong passwords in a row, when {@code free} pass. */
    static Duration waitAfter(int failures, int free) {
        if (failures < free) {
            return Duration.ZERO;
        }
        int doublings = Math.min(failures - free, 20);
        Duration wait = FIRST_WAIT.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    /** The failures that still count at {@code now}: none once a quiet spell has passed since the last. */
    static int counted(int failures, Instant last, Instant now, Duration quiet) {
        return last == null || !now.isBefore(last.plus(quiet)) ? 0 : failures;
    }

    /** What is left of the wait at {@code now}, zero when a try may go ahead. */
    static Duration remaining(int failures, Instant last, Instant now, int free, Duration quiet) {
        Duration wait = waitAfter(counted(failures, last, now, quiet), free);
        if (wait.isZero()) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, last.plus(wait));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Whether the address has had more wrong passwords than it gets for free. */
    synchronized boolean addressPressed(String address, Instant now) {
        Tally tally = addresses.get(address);
        return tally != null && counted(tally.failures(), tally.last(), now, ADDRESS_QUIET) >= ADDRESS_FREE;
    }

    /** Counts a wrong password, or an unknown email, from {@code address}. */
    public synchronized void addressFailed(String address, Instant now) {
        Tally tally = addresses.get(address);
        int failures = tally == null ? 0 : counted(tally.failures(), tally.last(), now, ADDRESS_QUIET);
        addresses.put(address, new Tally(failures + 1, now));
    }

    /**
     * Counts a try of the account from {@code address} as a failure, or refuses it while that
     * pair waits. In transactions of their own, before the password is hashed, so that no row
     * is held while that takes its time.
     */
    public void claim(User user, String address, Instant now) {
        boolean pressed = counted(user.failedLogins, user.lastFailedLoginAt, now, ACCOUNT_QUIET) >= ACCOUNT_FREE
                || addressPressed(address, now);
        int free = pressed ? PRESSED_FREE : PAIR_FREE;
        // In whole milliseconds: the database keeps microseconds and may round up, which
        // would put the failure after the moment it happened.
        Instant at = now.truncatedTo(ChronoUnit.MILLIS);
        for (int attempt = 1; attempt <= CLAIM_ATTEMPTS; attempt++) {
            LoginFailure pair = QuarkusTransaction.requiringNew().call(() ->
                    LoginFailure.<LoginFailure>find("userId = ?1 and address = ?2", user.id, address).firstResult());
            int seen = pair == null ? 0 : pair.failures;
            Instant last = pair == null ? null : pair.lastFailedAt;
            Duration wait = remaining(seen, last, now, free, ACCOUNT_QUIET);
            if (!wait.isZero()) {
                throw tooMany(wait);
            }
            int next = counted(seen, last, now, ACCOUNT_QUIET) + 1;
            if (pair == null) {
                if (insert(user.id, address, at)) {
                    return;
                }
            } else {
                long id = pair.id;
                int updated = QuarkusTransaction.requiringNew().call(() -> LoginFailure.update(
                        "failures = ?1, lastFailedAt = ?2 where id = ?3 and failures = ?4", next, at, id, seen));
                if (updated == 1) {
                    return;
                }
            }
            // Another try of the pair was counted at the same moment: go on from what it left.
        }
        throw tooMany(FIRST_WAIT);
    }

    /** The first failure of a pair. False when another try made the row at the same moment. */
    private static boolean insert(long userId, String address, Instant at) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                LoginFailure row = new LoginFailure();
                row.userId = userId;
                row.address = address;
                row.failures = 1;
                row.lastFailedAt = at;
                row.persist();
            });
            return true;
        } catch (PersistenceException e) {
            return false;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof PersistenceException) {
                return false;
            }
            throw e;
        }
    }

    /** The password was wrong: it counts for the account and for the address too. */
    public void failed(User user, String address, Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MILLIS);
        boolean fresh = counted(user.failedLogins, user.lastFailedLoginAt, now, ACCOUNT_QUIET) == 0;
        QuarkusTransaction.requiringNew().run(() -> User.update(
                "failedLogins = " + (fresh ? "1" : "failedLogins + 1") + ", lastFailedLoginAt = ?1 where id = ?2", at, user.id));
        addressFailed(address, now);
    }

    /**
     * The password was right: the pair is forgotten, along with pairs of the account that have
     * been quiet long enough, and the account starts again from zero.
     */
    public void succeeded(User user, String address, Instant now) {
        QuarkusTransaction.requiringNew().run(() -> {
            LoginFailure.delete("userId = ?1 and (address = ?2 or lastFailedAt < ?3)", user.id, address, now.minus(ACCOUNT_QUIET));
            User.update("failedLogins = 0, lastFailedLoginAt = null where id = ?1", user.id);
        });
    }

    /** Forgets every address. For tests. */
    public synchronized void clear() {
        addresses.clear();
    }

    /** How many addresses are remembered. For tests. */
    synchronized int size() {
        return addresses.size();
    }

    static ApiException tooMany(Duration wait) {
        return new ApiException(429, "too_many_attempts",
                "Too many wrong passwords. Try again in " + describe(wait) + ".").retryAfter(wait);
    }

    /** "1 second", "40 seconds", "3 minutes": rounded up, the way Retry-After is. */
    static String describe(Duration wait) {
        long seconds = Math.max((wait.toMillis() + 999) / 1000, 1);
        if (seconds < 60) {
            return seconds == 1 ? "1 second" : seconds + " seconds";
        }
        long minutes = (seconds + 59) / 60;
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
