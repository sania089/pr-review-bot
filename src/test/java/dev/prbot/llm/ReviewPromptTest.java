package dev.prbot.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewPromptTest {

    @Test
    void promptIsLoadedFromTheVersionedResource() {
        assertThat(ReviewPrompt.VERSION).matches("v\\d+");
        assertThat(ReviewPrompt.SYSTEM)
                .contains("L<n>")
                .contains("untrusted")
                .contains("high")
                .contains("Never report");
    }
}
