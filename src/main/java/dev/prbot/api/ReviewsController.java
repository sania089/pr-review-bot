package dev.prbot.api;

import dev.prbot.persistence.ReviewRecord;
import dev.prbot.persistence.ReviewRepository;
import dev.prbot.persistence.StatsRepository;
import dev.prbot.persistence.StatsWindow;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only dashboard endpoints. Controllers parse and respond; SQL lives in the repositories. */
@RestController
@RequestMapping("/api")
public class ReviewsController {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("QUEUED", "POSTED", "FAILED", "SKIPPED");

    private final ReviewRepository reviews;
    private final StatsRepository stats;

    public ReviewsController(ReviewRepository reviews, StatsRepository stats) {
        this.reviews = reviews;
        this.stats = stats;
    }

    public record Page(
            List<ReviewRecord> items, int page, int size, long totalItems, int totalPages) {}

    public record Stats(StatsWindow last7Days, StatsWindow last30Days) {}

    @GetMapping("/reviews")
    public Page list(
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page > 100_000 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "page must be >= 0 and size 1-" + MAX_PAGE_SIZE);
        }
        String repoFilter = blankToNull(repo);
        String statusFilter = blankToNull(status);
        if (statusFilter != null) {
            statusFilter = statusFilter.toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(statusFilter)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status");
            }
        }
        long total = reviews.count(repoFilter, statusFilter);
        List<ReviewRecord> items = reviews.findPage(repoFilter, statusFilter, size, page * size);
        return new Page(items, page, size, total, (int) ((total + size - 1) / size));
    }

    @GetMapping("/reviews/{id}")
    public ReviewRecord get(@PathVariable long id) {
        return reviews.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/stats")
    public Stats stats() {
        return new Stats(stats.window(7), stats.window(30));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
