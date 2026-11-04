package dev.prbot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.github.GitHubClient;
import dev.prbot.llm.LlmClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewServiceTest {

    private GitHubClient github;
    private LlmClient llm;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        github = mock(GitHubClient.class);
        llm = mock(LlmClient.class);
        service = new ReviewService(github, llm, TestProps.with(Provider.GEMINI));
    }

    private static String fileDiff(String path, String body) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n@@ -1,1 +1,2 @@\n keep\n+"
                + body
                + "\n";
    }

    @Test
    void doesNotCallTheModelWhenEveryFileIsFilteredOut() {
        when(github.fetchDiff("acme/w", 1))
                .thenReturn(fileDiff("package-lock.json", "x") + fileDiff("app.min.js", "y"));

        service.review("acme/w", 1, "sha");

        verify(llm, never()).review(anyString());
        verify(github, never()).postReview(any(), anyInt(), any(), any(), anyList());
    }

    @Test
    void sendsOnlyRelevantFilesToTheModel() {
        when(github.fetchDiff("acme/w", 1))
                .thenReturn(fileDiff("A.java", "a") + fileDiff("yarn.lock", "x"));
        when(llm.review(anyString())).thenReturn(new ReviewResult("fine", List.of()));

        service.review("acme/w", 1, "sha");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(llm).review(sent.capture());
        assertThat(sent.getValue()).contains("A.java").doesNotContain("yarn.lock");
    }

    @Test
    void mentionsFilesSkippedForSizeInTheSummary() {
        String small = fileDiff("Small.java", "s");
        String big = fileDiff("Big.java", "b".repeat(70_000));
        when(github.fetchDiff("acme/w", 1)).thenReturn(small + big);
        when(llm.review(anyString())).thenReturn(new ReviewResult("fine", List.of()));

        service.review("acme/w", 1, "sha");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(github).postReview(eq("acme/w"), eq(1), eq("sha"), summary.capture(), anyList());
        assertThat(summary.getValue()).startsWith("fine").contains("`Big.java`");
    }

    @Test
    void summaryHasNoNoteWhenNothingWasSkipped() {
        assertThat(ReviewService.skippedNote(List.of())).isEmpty();
    }

    @Test
    void skippedNoteListsAtMostTenFiles() {
        List<String> many = java.util.stream.IntStream.range(0, 13).mapToObj(i -> "f" + i).toList();

        assertThat(ReviewService.skippedNote(many))
                .contains("`f9`")
                .doesNotContain("`f10`")
                .contains("and 3 more");
    }
}
