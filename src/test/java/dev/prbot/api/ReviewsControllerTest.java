package dev.prbot.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.prbot.persistence.ReviewRecord;
import dev.prbot.persistence.ReviewRepository;
import dev.prbot.persistence.StatsRepository;
import dev.prbot.persistence.StatsWindow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewsControllerTest {

    private ReviewRepository reviews;
    private StatsRepository stats;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reviews = mock(ReviewRepository.class);
        stats = mock(StatsRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReviewsController(reviews, stats)).build();
    }

    private static ReviewRecord review(long id) {
        return new ReviewRecord(
                id,
                "acme/widgets",
                7,
                "abc123",
                "POSTED",
                "GEMINI",
                "m",
                "v1",
                100,
                20,
                1500L,
                3,
                null,
                OffsetDateTime.parse("2026-11-10T10:00:00Z"),
                OffsetDateTime.parse("2026-11-10T10:00:02Z"));
    }

    @Test
    void listsReviewsWithPagingAndPrUrl() throws Exception {
        when(reviews.count(null, null)).thenReturn(45L);
        when(reviews.findPage(null, null, 20, 20)).thenReturn(List.of(review(3)));

        mvc.perform(get("/api/reviews").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(
                        jsonPath("$.items[0].prUrl")
                                .value("https://github.com/acme/widgets/pull/7"))
                .andExpect(jsonPath("$.items[0].error").doesNotExist())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(45))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void passesRepoAndNormalisedStatusFilters() throws Exception {
        when(reviews.count("acme/widgets", "FAILED")).thenReturn(0L);
        when(reviews.findPage("acme/widgets", "FAILED", 10, 0)).thenReturn(List.of());

        mvc.perform(
                        get("/api/reviews")
                                .param("repo", "acme/widgets")
                                .param("status", "failed")
                                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void blankFiltersAreIgnored() throws Exception {
        when(reviews.findPage(null, null, 20, 0)).thenReturn(List.of());

        mvc.perform(get("/api/reviews").param("repo", " ").param("status", ""))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsBadPagingAndUnknownStatus() throws Exception {
        mvc.perform(get("/api/reviews").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews").param("page", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews").param("status", "DONE")).andExpect(status().isBadRequest());

        verify(reviews, never()).findPage(any(), any(), eq(20), eq(0));
    }

    @Test
    void returnsOneReviewOr404() throws Exception {
        when(reviews.findById(3)).thenReturn(Optional.of(review(3)));
        when(reviews.findById(4)).thenReturn(Optional.empty());

        mvc.perform(get("/api/reviews/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headSha").value("abc123"))
                .andExpect(jsonPath("$.commentsPosted").value(3));
        mvc.perform(get("/api/reviews/4")).andExpect(status().isNotFound());
    }

    @Test
    void returnsStatsForBothWindows() throws Exception {
        when(stats.window(7))
                .thenReturn(new StatsWindow(7, 10, 8, 1, 1, 24, 1 / 9.0, 900.0, 2500.0, 5000, 800));
        when(stats.window(30)).thenReturn(new StatsWindow(30, 0, 0, 0, 0, 0, 0, null, null, 0, 0));

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last7Days.reviews").value(10))
                .andExpect(jsonPath("$.last7Days.p95LatencyMs").value(2500.0))
                .andExpect(jsonPath("$.last30Days.p50LatencyMs").doesNotExist())
                .andExpect(jsonPath("$.last30Days.failureRate").value(0.0));
    }
}
