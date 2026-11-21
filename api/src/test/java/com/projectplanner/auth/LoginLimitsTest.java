package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginLimitsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void aFewFailuresPassThenTheWaitDoublesUpToFiveMinutes() {
        assertThat(LoginLimits.waitAfter(4, 5)).isZero();
        assertThat(LoginLimits.waitAfter(5, 5)).isEqualTo(Duration.ofSeconds(1));
        assertThat(LoginLimits.waitAfter(6, 5)).isEqualTo(Duration.ofSeconds(2));
        assertThat(LoginLimits.waitAfter(13, 5)).isEqualTo(Duration.ofSeconds(256));
        assertThat(LoginLimits.waitAfter(14, 5)).isEqualTo(Duration.ofMinutes(5));
        assertThat(LoginLimits.waitAfter(Integer.MAX_VALUE, 5)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theWaitIsCountedFromTheLastFailureAndForgottenAfterAQuietSpell() {
        Instant last = NOW.minusSeconds(1);
        assertThat(LoginLimits.remaining(7, last, NOW, 5, Duration.ofDays(1))).isEqualTo(Duration.ofSeconds(3));
        assertThat(LoginLimits.remaining(7, NOW.minusSeconds(4), NOW, 5, Duration.ofDays(1))).isZero();
        assertThat(LoginLimits.remaining(20, NOW.minusSeconds(60), NOW, 5, Duration.ofDays(1))).isEqualTo(Duration.ofMinutes(4));
        assertThat(LoginLimits.counted(20, NOW.minus(Duration.ofDays(1)), NOW, Duration.ofDays(1))).isZero();
        assertThat(LoginLimits.counted(20, NOW.minus(Duration.ofHours(23)), NOW, Duration.ofDays(1))).isEqualTo(20);
        assertThat(LoginLimits.remaining(0, null, NOW, 5, Duration.ofDays(1))).isZero();
    }

    @Test
    void anAddressWaitsAfterItsFreeFailuresAndARightPasswordGivesItsTryBack() {
        LoginLimits limits = new LoginLimits(100);
        for (int i = 0; i < LoginLimits.ADDRESS_FREE - 1; i++) {
            limits.claim("10.0.0.1", NOW);
        }
        // A right password in between does not count.
        limits.release(limits.claim("10.0.0.1", NOW));
        limits.claim("10.0.0.1", NOW);
        assertThatThrownBy(() -> limits.claim("10.0.0.1", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.code()).isEqualTo("too_many_attempts");
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(e.getMessage()).isEqualTo("Too many wrong passwords. Try again in 1 second.");
                });
        limits.claim("10.0.0.2", NOW);
        limits.claim("10.0.0.1", NOW.plusSeconds(1));
        // An hour without failures and the address starts from zero.
        Instant later = NOW.plus(LoginLimits.ADDRESS_QUIET).plusSeconds(1);
        for (int i = 0; i < LoginLimits.ADDRESS_FREE; i++) {
            limits.claim("10.0.0.1", later);
        }
    }

    @Test
    void releasingATryTakesOnlyThatOneOffWhenOthersCameIn() {
        LoginLimits limits = new LoginLimits(100);
        LoginLimits.Claim first = limits.claim("a", NOW);
        limits.claim("a", NOW);
        limits.release(first);
        for (int i = 0; i < LoginLimits.ADDRESS_FREE - 1; i++) {
            limits.claim("a", NOW);
        }
        assertThatThrownBy(() -> limits.claim("a", NOW)).isInstanceOf(ApiException.class);
    }

    @Test
    void remembersAtMostSoManyAddresses() {
        LoginLimits limits = new LoginLimits(3);
        for (String address : new String[] {"a", "b", "c", "d"}) {
            limits.claim(address, NOW);
        }
        assertThat(limits.size()).isEqualTo(3);
    }

    @Test
    void describesTheWaitRoundedUp() {
        assertThat(LoginLimits.describe(Duration.ofMillis(200))).isEqualTo("1 second");
        assertThat(LoginLimits.describe(Duration.ofMillis(39_100))).isEqualTo("40 seconds");
        assertThat(LoginLimits.describe(Duration.ofSeconds(60))).isEqualTo("1 minute");
        assertThat(LoginLimits.describe(Duration.ofSeconds(256))).isEqualTo("5 minutes");
    }
}
