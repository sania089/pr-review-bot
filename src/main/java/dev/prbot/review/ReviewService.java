package dev.prbot.review;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import dev.prbot.config.BotProperties;
import dev.prbot.github.GitHubClient;
import dev.prbot.github.GitHubClient.InlineComment;
import dev.prbot.llm.ClaudeClient;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final GitHubClient github;
    private final ClaudeClient claude;
    private final BotProperties props;

    public ReviewService(GitHubClient github, ClaudeClient claude, BotProperties props) {
        this.github = github;
        this.claude = claude;
        this.props = props;
    }

    @Async
    public void reviewAsync(String repo, int number, String headSha) {
        try {
            review(repo, number, headSha);
        } catch (Exception e) {
            log.error("Review failed for {}#{}", repo, number, e);
        }
    }

    void review(String repo, int number, String headSha) {
        String diff = github.fetchDiff(repo, number);
        boolean truncated = diff.length() > props.maxDiffChars();
        if (truncated) {
            diff = diff.substring(0, props.maxDiffChars());
        }

        DiffAnnotator.Annotated annotated = DiffAnnotator.annotate(diff);
        ReviewResult result = claude.review(annotated.text());

        List<InlineComment> comments = result.findings().stream()
                .filter(f -> annotated.canComment(f.path(), f.line()))
                .limit(props.maxComments())
                .map(f -> new InlineComment(f.path(), f.line(), "**[" + f.severity() + "]** " + f.body()))
                .toList();

        int dropped = result.findings().size() - comments.size();
        if (dropped > 0) {
            log.warn("{}#{}: dropped {} findings that pointed outside the diff or over the cap", repo, number, dropped);
        }

        String summary = result.summary()
                + (truncated ? "\n\n_Note: the diff was truncated, so part of this PR was not reviewed._" : "");
        github.postReview(repo, number, headSha, summary, comments);
        log.info("Posted review on {}#{} with {} inline comments", repo, number, comments.size());
    }
}
