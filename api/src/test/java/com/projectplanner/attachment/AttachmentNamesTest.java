package com.projectplanner.attachment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentNamesTest {

    @Test
    void aNameLosesItsFoldersItsControlCharactersAndItsQuotes() {
        assertThat(AttachmentService.cleanName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(AttachmentService.cleanName("C:\\Users\\ann\\Brief.pdf")).isEqualTo("Brief.pdf");
        assertThat(AttachmentService.cleanName("Brief \"final\"\r\n.pdf")).isEqualTo("Brief final.pdf");
        assertThat(AttachmentService.cleanName("  ")).isEqualTo("file");
        assertThat(AttachmentService.cleanName(null)).isEqualTo("file");
        assertThat(AttachmentService.cleanName("..")).isEqualTo("file");
        // A long name is cut, keeping its extension.
        String cut = AttachmentService.cleanName("a".repeat(300) + ".pdf");
        assertThat(cut).hasSize(255).endsWith(".pdf");
    }

    @Test
    void theDispositionHasAnAsciiNameAndTheRealOneEncoded() {
        assertThat(AttachmentContentResource.disposition("attachment", "Größe 1.txt"))
                .isEqualTo("attachment; filename=\"Gr__e 1.txt\"; filename*=UTF-8''Gr%C3%B6%C3%9Fe%201.txt");
        assertThat(AttachmentContentResource.disposition("inline", "a\\b.png"))
                .isEqualTo("inline; filename=\"a_b.png\"; filename*=UTF-8''a%5Cb.png");
    }
}
