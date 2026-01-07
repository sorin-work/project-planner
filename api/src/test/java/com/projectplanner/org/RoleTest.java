package com.projectplanner.org;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test
    void rolesAreOrderedByPower() {
        assertThat(Role.OWNER.atLeast(Role.MANAGER)).isTrue();
        assertThat(Role.MANAGER.atLeast(Role.MANAGER)).isTrue();
        assertThat(Role.MEMBER.atLeast(Role.MANAGER)).isFalse();
        assertThat(Role.CLIENT.atLeast(Role.MEMBER)).isFalse();
    }

    @Test
    void permissions() {
        assertThat(Role.MANAGER.canManage()).isTrue();
        assertThat(Role.MEMBER.canManage()).isFalse();
        assertThat(Role.MEMBER.isTeam()).isTrue();
        assertThat(Role.CLIENT.isTeam()).isFalse();
    }
}
