package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
    void anAddressIsPressedAfterItsFreeFailuresAndForgetsThemAfterAnHour() {
        LoginLimits limits = new LoginLimits(100);
        for (int i = 0; i < LoginLimits.ADDRESS_FREE - 1; i++) {
            limits.addressFailed("10.0.0.1", NOW);
        }
        assertThat(limits.addressPressed("10.0.0.1", NOW)).isFalse();
        limits.addressFailed("10.0.0.1", NOW);
        assertThat(limits.addressPressed("10.0.0.1", NOW)).isTrue();
        assertThat(limits.addressPressed("10.0.0.2", NOW)).isFalse();
        assertThat(limits.addressPressed("10.0.0.1", NOW.plus(LoginLimits.ADDRESS_QUIET))).isFalse();
    }

    @Test
    void remembersAtMostSoManyAddresses() {
        LoginLimits limits = new LoginLimits(3);
        for (String address : new String[] {"a", "b", "c", "d"}) {
            limits.addressFailed(address, NOW);
        }
        assertThat(limits.size()).isEqualTo(3);
    }

    @Test
    void theRefusalSaysHowLongToWait() {
        ApiException e = LoginLimits.tooMany(Duration.ofMillis(400));
        assertThat(e.status()).isEqualTo(429);
        assertThat(e.code()).isEqualTo("too_many_attempts");
        assertThat(e.retryAfter()).isEqualTo(Duration.ofMillis(400));
        assertThat(e.getMessage()).isEqualTo("Too many wrong passwords. Try again in 1 second.");
    }

    @Test
    void describesTheWaitRoundedUp() {
        assertThat(LoginLimits.describe(Duration.ofMillis(200))).isEqualTo("1 second");
        assertThat(LoginLimits.describe(Duration.ofMillis(39_100))).isEqualTo("40 seconds");
        assertThat(LoginLimits.describe(Duration.ofSeconds(60))).isEqualTo("1 minute");
        assertThat(LoginLimits.describe(Duration.ofSeconds(256))).isEqualTo("5 minutes");
    }
}
