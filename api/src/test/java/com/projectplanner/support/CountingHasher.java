package com.projectplanner.support;

import com.projectplanner.auth.PasswordHasher;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The password hasher in the API tests: the real one, which also notes every hash a password
 * was checked against, so that a test can see that a check happened where the answer alone
 * does not show it.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class CountingHasher extends PasswordHasher {

    private final List<String> checked = new CopyOnWriteArrayList<>();

    @Override
    public boolean matches(String password, String hash) {
        checked.add(String.valueOf(hash));
        return super.matches(password, hash);
    }

    /** The hashes checked against since the last call, oldest first. */
    public List<String> takeChecked() {
        List<String> taken = List.copyOf(checked);
        checked.clear();
        return taken;
    }
}
