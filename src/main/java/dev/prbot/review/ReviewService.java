package dev.prbot.review;

import dev.prbot.config.AsyncConfig;
import dev.prbot.config.BotProperties;
import dev.prbot.github.GitHubClient;
import dev.prbot.github.GitHubClient.InlineComment;
import dev.prbot.llm.LlmClient;
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
    private final BotProperties props;

    public ReviewService(GitHubClient github, LlmClient llm, BotProperties props) {
        this.github = github;
        this.llm = llm;
        this.props = props;
    }

    @Async(AsyncConfig.REVIEW_EXECUTOR)
    public void reviewAsync(String repo, int number, String headSha) {
        try {
            review(repo, number, headSha);
        } catch (Exception e) {
            log.error("Review failed for {}#{}", repo, number, e);
        }
    }

    void review(String repo, int number, String headSha) {
        List<FileDiff> all = FileDiff.split(github.fetchDiff(repo, number));
        List<FileDiff> relevant = DiffFilter.relevant(all);
        if (relevant.isEmpty()) {
            // TODO(day-3): record this as a SKIPPED review row.
            log.info(
                    "{}#{}: nothing to review, all {} changed files are filtered out",
                    repo,
                    number,
                    all.size());
            return;
        }

        DiffTruncator.Result fitted = DiffTruncator.fit(relevant, props.maxDiffChars());
        if (fitted.kept().isEmpty()) {
            // TODO(day-3): record this as a SKIPPED review row.
            log.warn("{}#{}: every changed file is larger than the diff size limit", repo, number);
            return;
        }
        log.info(
                "{}#{}: reviewing {} of {} files ({} filtered out, {} too large)",
                repo,
                number,
                fitted.kept().size(),
                all.size(),
                all.size() - relevant.size(),
                fitted.skippedPaths().size());

        DiffAnnotator.Annotated annotated = DiffAnnotator.annotate(fitted.joinedText());
        ReviewResult result = llm.review(annotated.text());

        List<InlineComment> comments =
                result.findings().stream()
                        .filter(f -> annotated.canComment(f.path(), f.line()))
                        .limit(props.maxComments())
                        .map(
                                f ->
                                        new InlineComment(
                                                f.path(),
                                                f.line(),
                                                "**[" + f.severity() + "]** " + f.body()))
                        .toList();

        int dropped = result.findings().size() - comments.size();
        if (dropped > 0) {
            log.warn(
                    "{}#{}: dropped {} findings that pointed outside the diff or over the cap",
                    repo,
                    number,
                    dropped);
        }

        String summary = result.summary() + skippedNote(fitted.skippedPaths());
        github.postReview(repo, number, headSha, summary, comments);
        log.info("Posted review on {}#{} with {} inline comments", repo, number, comments.size());
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
