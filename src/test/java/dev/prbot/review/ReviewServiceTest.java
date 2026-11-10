package dev.prbot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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
import dev.prbot.llm.LlmResponse;
import dev.prbot.persistence.ReviewRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewServiceTest {

    private static final long ID = 42;
    private static final long INST = 7;
    private static final LlmResponse LLM_OK =
            new LlmResponse(new ReviewResult("fine", List.of()), 100, 20);

    private GitHubClient github;
    private LlmClient llm;
    private ReviewRepository repository;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        github = mock(GitHubClient.class);
        llm = mock(LlmClient.class);
        repository = mock(ReviewRepository.class);
        service = new ReviewService(github, llm, repository, TestProps.with(Provider.GEMINI));
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
        when(github.fetchDiff(INST, "acme/w", 1))
                .thenReturn(fileDiff("package-lock.json", "x") + fileDiff("app.min.js", "y"));

        service.review(ID, INST, "acme/w", 1, "sha");

        verify(llm, never()).review(anyString());
        verify(github, never()).postReview(anyLong(), any(), anyInt(), any(), any(), anyList());
        verify(repository).markSkipped(eq(ID), anyString());
    }

    @Test
    void recordsPostedReviewWithUsage() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        when(llm.review(anyString())).thenReturn(LLM_OK);

        service.review(ID, INST, "acme/w", 1, "sha");

        verify(repository).markPosted(eq(ID), eq(0), eq(LLM_OK), anyLong());
    }

    @Test
    void recordsFailureWhenTheReviewThrows() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenThrow(new IllegalStateException("boom"));

        service.reviewAsync(ID, INST, "acme/w", 1, "sha");

        verify(repository).markFailed(ID, "IllegalStateException: boom");
        verify(repository, never()).markPosted(anyLong(), anyInt(), any(), anyLong());
    }

    @Test
    void sendsOnlyRelevantFilesToTheModel() {
        when(github.fetchDiff(INST, "acme/w", 1))
                .thenReturn(fileDiff("A.java", "a") + fileDiff("yarn.lock", "x"));
        when(llm.review(anyString())).thenReturn(LLM_OK);

        service.review(ID, INST, "acme/w", 1, "sha");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(llm).review(sent.capture());
        assertThat(sent.getValue()).contains("A.java").doesNotContain("yarn.lock");
    }

    @Test
    void mentionsFilesSkippedForSizeInTheSummary() {
        String small = fileDiff("Small.java", "s");
        String big = fileDiff("Big.java", "b".repeat(70_000));
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(small + big);
        when(llm.review(anyString())).thenReturn(LLM_OK);

        service.review(ID, INST, "acme/w", 1, "sha");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(github)
                .postReview(eq(INST), eq("acme/w"), eq(1), eq("sha"), summary.capture(), anyList());
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
