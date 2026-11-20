package com.projectplanner.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextTest {

    @Test
    void normalisesToLowerCaseWithoutTheSpaceAround() {
        assertThat(SearchText.normalise("  Home Page ")).isEqualTo("home page");
        assertThat(SearchText.normalise(null)).isEmpty();
    }

    @Test
    void wildcardsInTheNeedleMatchThemselves() {
        assertThat(SearchText.contains("100%")).isEqualTo("%100!%%");
        assertThat(SearchText.contains("a_b")).isEqualTo("%a!_b%");
        assertThat(SearchText.contains("wow!")).isEqualTo("%wow!!%");
        assertThat(SearchText.startsWith("a%_!")).isEqualTo("a!%!_!!%");
    }

    @Test
    void theSnippetIsTheTextAroundTheFirstMatch() {
        assertThat(SearchText.snippet("Ask the printer for a quote", "printer")).isEqualTo("Ask the printer for a quote");
        assertThat(SearchText.snippet("One\n\n  two   three", "two")).isEqualTo("One two three");
        assertThat(SearchText.snippet("nothing here", "absent")).isNull();
        assertThat(SearchText.snippet(null, "x")).isNull();
    }

    @Test
    void aLongTextIsCutAtWordsWithAnEllipsis() {
        String text = "word ".repeat(30) + "NEEDLE " + "tail ".repeat(40);
        String snippet = SearchText.snippet(text, "needle");
        assertThat(snippet).startsWith("…word ").contains("NEEDLE tail").endsWith("tail…");
        assertThat(snippet.length()).isLessThan(140);
    }
}
