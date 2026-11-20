package com.projectplanner.mail;

import com.projectplanner.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how often one client may ask for a mail, by the address the request comes
 * from. Kept in memory: it is a brake on scripts, not a record, and a restart may reset
 * it. Behind the web container the address is the one nginx saw, see BEHIND_PROXY.
 */
@ApplicationScoped
public class ClientThrottle {

    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_CLIENTS = 10_000;

    private final Clock clock;
    private final Map<String, Deque<Instant>> requests = new ConcurrentHashMap<>();

    public ClientThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Counts a request from {@code client}, or refuses it when there were too many. */
    public void check(String client) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        if (requests.size() > MAX_CLIENTS) {
            requests.values().removeIf(times -> {
                synchronized (times) {
                    return times.isEmpty() || !times.peekLast().isAfter(since);
                }
            });
        }
        Deque<Instant> times = requests.computeIfAbsent(client, key -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
                times.pollFirst();
            }
            if (times.size() >= LIMIT) {
                throw new ApiException(429, "too_many_requests", "Too many requests from this address. Try again in a few minutes.");
            }
            times.addLast(now);
        }
    }

    /** Forgets every client. For tests. */
    public void clear() {
        requests.clear();
    }
}
