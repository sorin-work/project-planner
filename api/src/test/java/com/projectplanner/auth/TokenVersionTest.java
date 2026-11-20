package com.projectplanner.auth;

import com.projectplanner.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TokenVersionTest {

    @Test
    void twoChangesAtTheSameMomentBothCount() throws Exception {
        Api.Account vic = Api.signup("Vic");
        int before = version(vic.userId());

        // Both read the account before either writes, as a reset and a change could.
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> changes = List.of(pool.submit(() -> change(vic.userId(), bothLoaded)),
                    pool.submit(() -> change(vic.userId(), bothLoaded)));
            for (Future<?> change : changes) {
                change.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(version(vic.userId())).isEqualTo(before + 2);
    }

    private static void change(long userId, CyclicBarrier bothLoaded) {
        QuarkusTransaction.requiringNew().run(() -> {
            User user = User.findById(userId);
            try {
                bothLoaded.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            user.changePassword(user.passwordHash, Instant.now());
        });
    }

    private static int version(long userId) {
        return QuarkusTransaction.requiringNew().call(() -> User.<User>findById(userId).tokenVersion);
    }
}
