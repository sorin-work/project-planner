package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brakes on guessing passwords, per account and per client address.
 * <p>
 * A few wrong passwords pass. After that each further try waits, counted from the last
 * wrong one: a second, then two, four and so on, up to {@link #MAX_WAIT}. A try during the
 * wait is refused with {@code 429 too_many_attempts} before the password is looked at, so
 * that the answer says nothing about it, and it does not count as a failure: the wait never
 * grows from the owner trying again. An account is never locked: after at most
 * {@link #MAX_WAIT} a try goes through, and a password reset sets the count back to zero.
 * <p>
 * A try is counted as a failure before the password is checked, and taken back when it was
 * right. That way a burst of tries at the same moment cannot all get past the wait: for the
 * account the count is moved on by an update that only matches the count it read, and for
 * the address the count is changed under a lock.
 * <p>
 * The counts per account are in the database (see {@link User#failedLogins}), so that they
 * hold across a restart and across copies of the API. The counts per address are in memory,
 * like {@link com.projectplanner.mail.ClientThrottle}: they stop one machine from trying
 * many accounts, and a restart resets them. An address gets more free failures than an
 * account, since a whole office may sign in from one. Both forget the failures after a quiet
 * spell, so that a typo next month does not wait for one from last year.
 */
@ApplicationScoped
public class LoginLimits {

    static final int ACCOUNT_FREE = 5;
    static final int ADDRESS_FREE = 20;
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    static final Duration MAX_WAIT = Duration.ofMinutes(5);
    static final Duration ACCOUNT_QUIET = Duration.ofDays(1);
    static final Duration ADDRESS_QUIET = Duration.ofHours(1);
    static final int MAX_CLIENTS = 10_000;
    private static final int CLAIM_ATTEMPTS = 3;

    /** The failures of one address and when the last one was. */
    record Tally(int failures, Instant last) {
    }

    /** A try of an address, counted as a failure until {@link #release} takes it back. */
    public record Claim(String address, Tally before, Tally after) {
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

    /** Counts a try of {@code address} as a failure, or refuses it while the address waits. */
    public synchronized Claim claim(String address, Instant now) {
        Tally before = addresses.get(address);
        int failures = before == null ? 0 : before.failures();
        Instant last = before == null ? null : before.last();
        Duration wait = remaining(failures, last, now, ADDRESS_FREE, ADDRESS_QUIET);
        if (!wait.isZero()) {
            throw tooMany(wait);
        }
        Tally after = new Tally(counted(failures, last, now, ADDRESS_QUIET) + 1, now);
        addresses.put(address, after);
        return new Claim(address, before, after);
    }

    /** Takes back a try of an address that was not a wrong password. */
    public synchronized void release(Claim claim) {
        Tally current = addresses.get(claim.address());
        if (current == claim.after()) {
            if (claim.before() == null) {
                addresses.remove(claim.address());
            } else {
                addresses.put(claim.address(), claim.before());
            }
        } else if (current != null) {
            // Other tries were counted since: only this one comes off.
            addresses.put(claim.address(), new Tally(Math.max(current.failures() - 1, 0), current.last()));
        }
    }

    /**
     * Counts a try of the account as a failure, or refuses it while the account waits. The
     * count is moved on in a transaction of its own, before the password is hashed, so that
     * the row is not held while that takes its time.
     */
    public void claim(User user, Instant now) {
        int seen = user.failedLogins;
        Instant last = user.lastFailedLoginAt;
        for (int attempt = 1; ; attempt++) {
            Duration wait = remaining(seen, last, now, ACCOUNT_FREE, ACCOUNT_QUIET);
            if (!wait.isZero()) {
                throw tooMany(wait);
            }
            int next = counted(seen, last, now, ACCOUNT_QUIET) + 1;
            int expected = seen;
            // In whole milliseconds: the database keeps microseconds and may round up, which
            // would put the failure after the moment it happened.
            Instant at = now.truncatedTo(ChronoUnit.MILLIS);
            int updated = QuarkusTransaction.requiringNew().call(() -> User.update(
                    "failedLogins = ?1, lastFailedLoginAt = ?2 where id = ?3 and failedLogins = ?4",
                    next, at, user.id, expected));
            if (updated == 1) {
                return;
            }
            if (attempt == CLAIM_ATTEMPTS) {
                throw tooMany(FIRST_WAIT);
            }
            // Another try of the account was counted at the same moment: go on from what it left.
            User fresh = QuarkusTransaction.requiringNew().call(() -> User.<User>findById(user.id));
            seen = fresh.failedLogins;
            last = fresh.lastFailedLoginAt;
        }
    }

    /** The password was right: the account starts again from zero and the address gets its try back. */
    public void succeeded(User user, Claim claim) {
        QuarkusTransaction.requiringNew().run(() ->
                User.update("failedLogins = 0, lastFailedLoginAt = null where id = ?1", user.id));
        release(claim);
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
