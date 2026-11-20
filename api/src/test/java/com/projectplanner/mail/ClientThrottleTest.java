package com.projectplanner.mail;

import com.projectplanner.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientThrottleTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void refusesTheEleventhRequestOfAClientInTheWindow() {
        ClientThrottle throttle = new ClientThrottle(clock, 100);
        for (int i = 0; i < ClientThrottle.LIMIT; i++) {
            throttle.check("10.0.0.1");
        }
        assertThatThrownBy(() -> throttle.check("10.0.0.1")).isInstanceOf(ApiException.class);
        throttle.check("10.0.0.2");
    }

    @Test
    void forgetsTheClientThatAskedLeastRecentlyWhenFull() {
        ClientThrottle throttle = new ClientThrottle(clock, 3);
        for (int i = 0; i < ClientThrottle.LIMIT; i++) {
            throttle.check("a");
        }
        throttle.check("b");
        throttle.check("c");
        // "a" is the one that asked least recently once "b" and "c" came in.
        throttle.check("d");
        assertThat(throttle.size()).isEqualTo(3);
        throttle.check("a");
        assertThat(throttle.size()).isEqualTo(3);

        // A flood of new addresses, all still in their window, does not grow it.
        for (int i = 0; i < 1000; i++) {
            throttle.check("flood-" + i);
        }
        assertThat(throttle.size()).isEqualTo(3);
    }
}
