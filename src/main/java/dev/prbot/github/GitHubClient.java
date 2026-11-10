package dev.prbot.github;

import dev.prbot.http.Retrier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class GitHubClient {

    private final RestClient http;
    private final Retrier retrier;
    private final TokenProvider tokens;

    public GitHubClient(TokenProvider tokens, RestClient.Builder builder, Retrier retrier) {
        this.tokens = tokens;
        this.retrier = retrier;
        this.http = GitHubHttp.client(builder);
    }

    public String fetchDiff(long installationId, String repo, int number) {
        String[] r = split(repo);
        String token = tokens.tokenFor(installationId);
        return retrier.call(
                () ->
                        http.get()
                                .uri("/repos/{owner}/{name}/pulls/{n}", r[0], r[1], number)
                                .headers(h -> h.setBearerAuth(token))
                                .accept(MediaType.valueOf("application/vnd.github.v3.diff"))
                                .retrieve()
                                .body(String.class));
    }

    /**
     * Reads one file at a ref (the PR's base branch, so a PR cannot change its own review rules).
     * Empty if the file does not exist; any other error is thrown.
     */
    public Optional<String> fetchFile(long installationId, String repo, String path, String ref) {
        String[] r = split(repo);
        String token = tokens.tokenFor(installationId);
        try {
            return Optional.ofNullable(
                    retrier.call(
                            () ->
                                    http.get()
                                            .uri(
                                                    b ->
                                                            b.path(
                                                                            "/repos/{owner}/{name}/contents/{path}")
                                                                    .queryParamIfPresent(
                                                                            "ref",
                                                                            Optional.ofNullable(ref)
                                                                                    .filter(
                                                                                            s ->
                                                                                                    !s
                                                                                                            .isBlank()))
                                                                    .build(r[0], r[1], path))
                                            .headers(h -> h.setBearerAuth(token))
                                            .accept(
                                                    MediaType.valueOf(
                                                            "application/vnd.github.raw+json"))
                                            .retrieve()
                                            .body(String.class)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
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
            long installationId,
            String repo,
            int number,
            String headSha,
            String summary,
            List<InlineComment> comments) {
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
        String token = tokens.tokenFor(installationId);
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
                                .headers(h -> h.setBearerAuth(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .toBodilessEntity());
    }
}
