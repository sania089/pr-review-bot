package dev.prbot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.github.GitHubClient;
import dev.prbot.github.GitHubClient.InlineComment;
import dev.prbot.llm.LlmClient;
import dev.prbot.llm.LlmResponse;
import dev.prbot.llm.QuotaExceededException;
import dev.prbot.llm.QuotaGuard;
import dev.prbot.persistence.ReviewRepository;
import java.util.List;
import java.util.Optional;
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
    private QuotaGuard quota;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        github = mock(GitHubClient.class);
        llm = mock(LlmClient.class);
        repository = mock(ReviewRepository.class);
        quota = mock(QuotaGuard.class);
        when(llm.promptVersion()).thenReturn("v1");
        when(github.fetchFile(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        service =
                new ReviewService(github, llm, repository, quota, TestProps.with(Provider.GEMINI));
    }

    private void review() {
        service.review(ID, INST, "acme/w", 1, "sha", "main");
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

    private static LlmResponse finding(String path, int line, String severity) {
        return new LlmResponse(
                new ReviewResult(
                        "s", List.of(new ReviewResult.Finding(path, line, severity, "problem"))),
                10,
                5);
    }

    private List<InlineComment> postedComments() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InlineComment>> captor = ArgumentCaptor.forClass(List.class);
        verify(github).postReview(anyLong(), any(), anyInt(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void doesNotCallTheModelWhenEveryFileIsFilteredOut() {
        when(github.fetchDiff(INST, "acme/w", 1))
                .thenReturn(fileDiff("package-lock.json", "x") + fileDiff("app.min.js", "y"));

        review();

        verify(llm, never()).review(anyString());
        verify(quota, never()).reserve(anyInt());
        verify(github, never()).postReview(anyLong(), any(), anyInt(), any(), any(), anyList());
        verify(repository).markSkipped(eq(ID), anyString());
    }

    @Test
    void recordsPostedReviewWithUsageAndPromptVersion() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        when(llm.review(anyString())).thenReturn(LLM_OK);

        review();

        verify(quota).reserve(1);
        verify(quota).awaitSlot();
        verify(repository).markPosted(eq(ID), eq(0), eq(100), eq(20), anyLong(), eq("v1"));
    }

    @Test
    void recordsFailureWhenTheReviewThrows() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenThrow(new IllegalStateException("boom"));

        service.reviewAsync(ID, INST, "acme/w", 1, "sha", "main");

        verify(repository).markFailed(ID, "IllegalStateException: boom");
        verify(repository, never())
                .markPosted(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
    }

    @Test
    void sendsOnlyRelevantFilesToTheModel() {
        when(github.fetchDiff(INST, "acme/w", 1))
                .thenReturn(fileDiff("A.java", "a") + fileDiff("yarn.lock", "x"));
        when(llm.review(anyString())).thenReturn(LLM_OK);

        review();

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

        review();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(github)
                .postReview(eq(INST), eq("acme/w"), eq(1), eq("sha"), summary.capture(), anyList());
        assertThat(summary.getValue()).startsWith("fine").contains("`Big.java`");
    }

    @Test
    void skipsFilesMatchedByIgnoreInTheRepoConfig() {
        when(github.fetchDiff(INST, "acme/w", 1))
                .thenReturn(fileDiff("A.java", "a") + fileDiff("docs/guide.md", "d"));
        when(github.fetchFile(INST, "acme/w", ".prbot.yml", "main"))
                .thenReturn(Optional.of("ignore:\n  - docs/\n"));
        when(llm.review(anyString())).thenReturn(LLM_OK);

        review();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(llm).review(sent.capture());
        assertThat(sent.getValue()).contains("A.java").doesNotContain("guide.md");
    }

    @Test
    void invalidRepoConfigFallsBackToDefaultsAndIsMentionedInTheSummary() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        when(github.fetchFile(INST, "acme/w", ".prbot.yml", "main"))
                .thenReturn(Optional.of("strictness: paranoid"));
        when(llm.review(anyString())).thenReturn(LLM_OK);

        review();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(github)
                .postReview(eq(INST), eq("acme/w"), eq(1), eq("sha"), summary.capture(), anyList());
        assertThat(summary.getValue()).contains(".prbot.yml").contains("defaults were used");
        verify(repository).markPosted(eq(ID), eq(0), anyInt(), anyInt(), anyLong(), any());
    }

    @Test
    void normalStrictnessDropsLowSeverityFindings() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        when(llm.review(anyString())).thenReturn(finding("A.java", 2, "low"));

        review();

        assertThat(postedComments()).isEmpty();
    }

    @Test
    void highStrictnessPostsLowSeverityFindings() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        when(github.fetchFile(INST, "acme/w", ".prbot.yml", "main"))
                .thenReturn(Optional.of("strictness: high"));
        when(llm.review(anyString())).thenReturn(finding("A.java", 2, "low"));

        review();

        assertThat(postedComments())
                .singleElement()
                .satisfies(c -> assertThat(c.line()).isEqualTo(2));
    }

    @Test
    void dropsFindingsOutsideTheDiff() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        when(llm.review(anyString())).thenReturn(finding("A.java", 99, "high"));

        review();

        assertThat(postedComments()).isEmpty();
    }

    @Test
    void reviewsABigPullRequestInSeveralRequestsAndSumsUsage() {
        String a = fileDiff("A.java", "a".repeat(15_000));
        String b = fileDiff("B.java", "b".repeat(15_000));
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(a + b); // batch limit is 24000
        when(llm.review(anyString())).thenReturn(LLM_OK);

        review();

        verify(quota).reserve(2);
        verify(llm, times(2)).review(anyString());
        verify(repository).markPosted(eq(ID), eq(0), eq(200), eq(40), anyLong(), eq("v1"));
    }

    @Test
    void skipsTheReviewWhenTheDailyQuotaIsUsedUp() {
        when(github.fetchDiff(INST, "acme/w", 1)).thenReturn(fileDiff("A.java", "a"));
        doThrow(new QuotaExceededException("daily limit")).when(quota).reserve(anyInt());

        review();

        verify(llm, never()).review(anyString());
        verify(github, never()).postReview(anyLong(), any(), anyInt(), any(), any(), anyList());
        verify(repository).markSkipped(ID, "daily limit");
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
