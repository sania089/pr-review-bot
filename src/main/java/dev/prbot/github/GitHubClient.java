package dev.prbot.github;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.prbot.config.BotProperties;

@Component
public class GitHubClient {

    private final RestClient http;

    public GitHubClient(BotProperties props, RestClient.Builder builder) {
        this.http = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + props.githubToken())
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public String fetchDiff(String repo, int number) {
        return http.get()
                .uri("/repos/{repo}/pulls/{n}", repo, number)
                .accept(MediaType.valueOf("application/vnd.github.v3.diff"))
                .retrieve()
                .body(String.class);
    }

    public record InlineComment(String path, int line, String body) {
    }

    public void postReview(String repo, int number, String headSha, String summary, List<InlineComment> comments) {
        List<Map<String, Object>> inline = comments.stream()
                .<Map<String, Object>>map(c -> Map.of(
                        "path", c.path(),
                        "line", c.line(),
                        "side", "RIGHT",
                        "body", c.body()))
                .toList();

        http.post()
                .uri("/repos/{repo}/pulls/{n}/reviews", repo, number)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "commit_id", headSha,
                        "body", summary,
                        "event", "COMMENT",
                        "comments", inline))
                .retrieve()
                .toBodilessEntity();
    }
}
