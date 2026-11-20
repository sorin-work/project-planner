package com.projectplanner.project;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectCodesTest {

    @Test
    void proposesTheNextFreeNumber() {
        assertThat(ProjectCodes.next("ACME-WEB", List.of("ACME-WEB"))).isEqualTo("ACME-WEB-2");
        assertThat(ProjectCodes.next("ACME-WEB", List.of("ACME-WEB", "ACME-WEB-2", "acme-web-3"))).isEqualTo("ACME-WEB-4");
        assertThat(ProjectCodes.next("acme-web ", List.of())).isEqualTo("ACME-WEB-2");
    }

    @Test
    void countsOnFromACodeThatIsNumberedAlready() {
        assertThat(ProjectCodes.next("ACME-WEB-2", List.of("ACME-WEB", "ACME-WEB-2"))).isEqualTo("ACME-WEB-3");
        assertThat(ProjectCodes.next("ACME-WEB-2", List.of("ACME-WEB-2", "ACME-WEB-3"))).isEqualTo("ACME-WEB-4");
        assertThat(ProjectCodes.next("Q4-2026", List.of("Q4-2026"))).isEqualTo("Q4-2027");
        // Digits that are the whole code, or part of a word, are not a number to count on from.
        assertThat(ProjectCodes.next("2026", List.of("2026"))).isEqualTo("2026-2");
        assertThat(ProjectCodes.next("WEB2", List.of("WEB2"))).isEqualTo("WEB2-2");
    }

    @Test
    void cutsALongCodeSoThatTheResultStillFits() {
        assertThat(ProjectCodes.next("ABCDEFGHIJKLMNOP", List.of("ABCDEFGHIJKLMNOP"))).isEqualTo("ABCDEFGHIJKLMN-2");
        assertThat(ProjectCodes.next("GLOBEX-PORTAL-EU", List.of("GLOBEX-PORTAL-EU"))).isEqualTo("GLOBEX-PORTAL-2");
        assertThat(ProjectCodes.next("ABCDEFGHIJKLM-9", List.of("ABCDEFGHIJKLM-9"))).isEqualTo("ABCDEFGHIJKLM-10");
        assertThat(ProjectCodes.next("ABCDEFGHIJKLMN-9", List.of("ABCDEFGHIJKLMN-9"))).isEqualTo("ABCDEFGHIJKLM-10");
    }

    @Test
    void everyProposalIsAValidCodeAndNotTaken() {
        Set<String> taken = new java.util.HashSet<>(Set.of("A", "LONG-PROJECT-CODE"));
        for (String source : List.of("A", "LONG-PROJECT-CODE", "X-1", "A-B-C-D-E-F-G-H", "Z9")) {
            IntStream.range(0, 120).forEach(i -> {
                String code = ProjectCodes.next(source, taken);
                assertThat(code).matches("[A-Z0-9][A-Z0-9-]{0,15}").doesNotContain("--");
                assertThat(taken.add(code)).as(code).isTrue();
            });
        }
    }
}
