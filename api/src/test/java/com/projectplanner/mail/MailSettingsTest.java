package com.projectplanner.mail;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSettingsTest {

    private static MailSettings on(String from, String appUrl) {
        return new MailSettings(Optional.of("smtp.example.com"), Optional.ofNullable(from), Optional.ofNullable(appUrl), false);
    }

    @Test
    void linksAreBuiltFromAppUrlWithTheTokenAfterTheHash() {
        MailSettings settings = on("planner@example.com", "https://planner.example.com/");
        assertThat(settings.link("/reset", "abc")).isEqualTo("https://planner.example.com/reset#token=abc");
    }

    @Test
    void mailNeedsAFromAddressAndTheAddressOfTheWebApp() {
        assertThatThrownBy(() -> on(null, "https://planner.example.com")).hasMessageContaining("MAIL_FROM");
        assertThatThrownBy(() -> on("planner@example.com", null)).hasMessageContaining("APP_URL");
        assertThatThrownBy(() -> on("planner@example.com", "planner.example.com")).hasMessageContaining("APP_URL");
        assertThatThrownBy(() -> on("planner@example.com", "javascript:alert(1)")).hasMessageContaining("APP_URL");
        assertThatThrownBy(() -> on("planner@example.com", "https://planner.example.com/?x=1")).hasMessageContaining("APP_URL");
    }

    @Test
    void withoutAHostMailIsOffAndSoIsTheLinkRule() {
        MailSettings settings = new MailSettings(Optional.empty(), Optional.empty(), Optional.empty(), true);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.inviteLinkRequired()).isFalse();
    }
}
