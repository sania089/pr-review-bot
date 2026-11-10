package dev.prbot.review;

import dev.prbot.config.AsyncConfig;
import dev.prbot.config.BotProperties;
import dev.prbot.github.GitHubClient;
import dev.prbot.github.GitHubClient.InlineComment;
import dev.prbot.llm.LlmClient;
import dev.prbot.llm.LlmResponse;
import dev.prbot.llm.QuotaExceededException;
import dev.prbot.llm.QuotaGuard;
import dev.prbot.persistence.ReviewRepository;
import dev.prbot.review.ReviewResult.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final GitHubClient github;
    private final LlmClient llm;
    private final ReviewRepository reviews;
    private final QuotaGuard quota;
    private final BotProperties props;

    public ReviewService(
            GitHubClient github,
            LlmClient llm,
            ReviewRepository reviews,
            QuotaGuard quota,
            BotProperties props) {
        this.github = github;
        this.llm = llm;
        this.reviews = reviews;
        this.quota = quota;
        this.props = props;
    }

    @Async(AsyncConfig.REVIEW_EXECUTOR)
    public void reviewAsync(
            long id, long installationId, String repo, int number, String headSha, String baseRef) {
        try {
            review(id, installationId, repo, number, headSha, baseRef);
        } catch (Exception e) {
            log.error("Review failed for {}#{}", repo, number, e);
            reviews.markFailed(id, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void review(
            long id, long installationId, String repo, int number, String headSha, String baseRef) {
        List<FileDiff> all = FileDiff.split(github.fetchDiff(installationId, repo, number));
        RepoConfig.Loaded loaded =
                RepoConfig.parse(
                        github.fetchFile(installationId, repo, RepoConfig.FILE_NAME, baseRef)
                                .orElse(null));
        RepoConfig config = loaded.config();

        List<FileDiff> relevant =
                DiffFilter.relevant(all).stream().filter(f -> !config.ignores(f.path())).toList();
        if (relevant.isEmpty()) {
            log.info(
                    "{}#{}: nothing to review, all {} changed files are filtered out",
                    repo,
                    number,
                    all.size());
            reviews.markSkipped(id, "no reviewable files in the diff");
            return;
        }

        DiffTruncator.Result fitted = DiffTruncator.fit(relevant, props.maxDiffChars());
        if (fitted.kept().isEmpty()) {
            log.warn("{}#{}: every changed file is larger than the diff size limit", repo, number);
            reviews.markSkipped(id, "every changed file is over the diff size limit");
            return;
        }
        List<List<FileDiff>> batches = DiffBatcher.batches(fitted.kept(), props.maxBatchChars());
        log.info(
                "{}#{}: reviewing {} of {} files in {} request(s) ({} filtered out, {} too large)",
                repo,
                number,
                fitted.kept().size(),
                all.size(),
                batches.size(),
                all.size() - relevant.size(),
                fitted.skippedPaths().size());

        try {
            quota.reserve(batches.size());
        } catch (QuotaExceededException e) {
            log.warn("{}#{}: skipped, {}", repo, number, e.getMessage());
            reviews.markSkipped(id, e.getMessage());
            return;
        }

        List<String> summaries = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        long latencyMs = 0;
        int outsideDiff = 0;
        for (List<FileDiff> batch : batches) {
            DiffAnnotator.Annotated annotated = DiffAnnotator.annotate(DiffBatcher.join(batch));
            quota.awaitSlot();
            long started = System.nanoTime();
            LlmResponse response = llm.review(annotated.text());
            latencyMs += (System.nanoTime() - started) / 1_000_000;
            inputTokens += response.inputTokens();
            outputTokens += response.outputTokens();

            ReviewResult result = response.review();
            summaries.add(result.summary());
            for (Finding f : result.findings()) {
                if (annotated.canComment(f.path(), f.line())) {
                    findings.add(f);
                } else {
                    outsideDiff++;
                }
            }
        }

        // Most severe first, so the comment cap drops the least important findings.
        int cap = Math.min(props.maxComments(), config.strictness().maxComments());
        List<InlineComment> comments =
                findings.stream()
                        .filter(f -> config.strictness().allows(f.severity()))
                        .sorted(
                                Comparator.comparingInt(
                                                (Finding f) -> RepoConfig.rank(f.severity()))
                                        .reversed())
                        .limit(cap)
                        .map(
                                f ->
                                        new InlineComment(
                                                f.path(),
                                                f.line(),
                                                "**[" + f.severity() + "]** " + f.body()))
                        .toList();
        log.info(
                "{}#{}: {} findings, {} outside the diff, {} posted (strictness {})",
                repo,
                number,
                findings.size() + outsideDiff,
                outsideDiff,
                comments.size(),
                config.strictness());

        String summary =
                String.join("\n\n", summaries)
                        + configNote(loaded.warning())
                        + skippedNote(fitted.skippedPaths());
        github.postReview(installationId, repo, number, headSha, summary, comments);
        reviews.markPosted(
                id, comments.size(), inputTokens, outputTokens, latencyMs, llm.promptVersion());
        log.info("Posted review on {}#{} with {} inline comments", repo, number, comments.size());
    }

    private static String configNote(String warning) {
        return warning == null ? "" : "\n\n_Note: " + warning + "_";
    }

    private static final int MAX_LISTED_FILES = 10;

    static String skippedNote(List<String> skipped) {
        if (skipped.isEmpty()) {
            return "";
        }
        String listed =
                skipped.stream()
                        .limit(MAX_LISTED_FILES)
                        .map(p -> "`" + p + "`")
                        .collect(Collectors.joining(", "));
        int more = skipped.size() - MAX_LISTED_FILES;
        return "\n\n_Note: "
                + skipped.size()
                + " file(s) were not reviewed because the diff is over the size limit: "
                + listed
                + (more > 0 ? " and " + more + " more" : "")
                + "._";
    }
}
