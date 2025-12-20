package com.projectplanner.comment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    @Test
    void findsMentions() {
        assertThat(MentionParser.handles("@ana can you look at this with @ben.k?")).containsExactly("ana", "ben.k");
    }

    @Test
    void lowerCasesAndDeduplicates() {
        assertThat(MentionParser.handles("@Ana and @ana again")).containsExactly("ana");
    }

    @Test
    void stripsTrailingPunctuation() {
        assertThat(MentionParser.handles("Thanks @ana.")).containsExactly("ana");
        assertThat(MentionParser.handles("(@ben) and @cara, done")).containsExactly("ben", "cara");
    }

    @Test
    void nothingToFind() {
        assertThat(MentionParser.handles(null)).isEmpty();
        assertThat(MentionParser.handles("no mentions @ here")).isEmpty();
    }
}
