package com.projectplanner.support;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application's clock in the API tests. It shows the real time until a test
 * fixes it, to check what happens at a given hour. Sign people up before fixing
 * it (their tokens are checked against the real time) and reset it afterwards.
 */
@ApplicationScoped
public class TestClock {

    private static volatile Instant fixed;

    public static void fix(Instant instant) {
        fixed = instant;
    }

    public static void reset() {
        fixed = null;
    }

    @Produces
    @Singleton
    Clock clock() {
        return new Adjustable(ZoneOffset.UTC);
    }

    private static final class Adjustable extends Clock {

        private final ZoneId zone;

        private Adjustable(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new Adjustable(other);
        }

        @Override
        public Instant instant() {
            Instant now = fixed;
            return now != null ? now : Instant.now();
        }
    }
}
