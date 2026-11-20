package com.projectplanner.mail;

import com.projectplanner.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limits how often one client may ask for a mail, by the address the request comes
 * from. Kept in memory: it is a brake on scripts, not a record, and a restart may reset
 * it. Behind the web container the address is the one nginx saw, see BEHIND_PROXY.
 * <p>
 * It remembers at most {@link #MAX_CLIENTS} clients. Past that the one that asked least
 * recently is forgotten, so that a flood of addresses cannot make it grow without end.
 * Such a flood can push a client out early, which only means it gets a fresh count.
 */
@ApplicationScoped
public class ClientThrottle {

    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_CLIENTS = 10_000;

    private final Clock clock;
    private final Map<String, Deque<Instant>> requests;

    @Inject
    public ClientThrottle(Clock clock) {
        this(clock, MAX_CLIENTS);
    }

    ClientThrottle(Clock clock, int maxClients) {
        this.clock = clock;
        // In order of use, so that the eldest entry is the client that asked least recently.
        this.requests = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > maxClients;
            }
        };
    }

    /** Counts a request from {@code client}, or refuses it when there were too many. */
    public synchronized void check(String client) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        Deque<Instant> times = requests.computeIfAbsent(client, key -> new ArrayDeque<>());
        while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
            times.pollFirst();
        }
        if (times.size() >= LIMIT) {
            throw new ApiException(429, "too_many_requests", "Too many requests from this address. Try again in a few minutes.");
        }
        times.addLast(now);
    }

    /** How many clients are remembered. For tests. */
    synchronized int size() {
        return requests.size();
    }

    /** Forgets every client. For tests. */
    public synchronized void clear() {
        requests.clear();
    }
}
