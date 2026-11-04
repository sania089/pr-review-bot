package dev.prbot.github;

import dev.prbot.config.BotProperties;
import dev.prbot.http.Retrier;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GitHubClient {

    private final RestClient http;
    private final Retrier retrier;

    public GitHubClient(BotProperties props, RestClient.Builder builder, Retrier retrier) {
        this.retrier = retrier;
        this.http =
                builder.baseUrl("https://api.github.com")
                        .defaultHeader("Authorization", "Bearer " + props.githubToken())
                        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                        .build();
    }

    public String fetchDiff(String repo, int number) {
        String[] r = split(repo);
        return retrier.call(
                () ->
                        http.get()
                                .uri("/repos/{owner}/{name}/pulls/{n}", r[0], r[1], number)
                                .accept(MediaType.valueOf("application/vnd.github.v3.diff"))
                                .retrieve()
                                .body(String.class));
    }

    /**
     * URI variables are encoded strictly, so a single "{repo}" holding "owner/name" would become
     * owner%2Fname, which GitHub answers with 404. Owner and name are sent as separate variables.
     */
    private static String[] split(String fullName) {
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Repository must be in owner/name form");
        }
        return parts;
    }

    public record InlineComment(String path, int line, String body) {}

    public void postReview(
            String repo, int number, String headSha, String summary, List<InlineComment> comments) {
        List<Map<String, Object>> inline =
                comments.stream()
                        .<Map<String, Object>>map(
                                c ->
                                        Map.of(
                                                "path", c.path(),
                                                "line", c.line(),
                                                "side", "RIGHT",
                                                "body", c.body()))
                        .toList();

        String[] r = split(repo);
        Map<String, Object> body =
                Map.of(
                        "commit_id", headSha,
                        "body", summary,
                        "event", "COMMENT",
                        "comments", inline);
        retrier.run(
                () ->
                        http.post()
                                .uri("/repos/{owner}/{name}/pulls/{n}/reviews", r[0], r[1], number)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .toBodilessEntity());
    }
}
