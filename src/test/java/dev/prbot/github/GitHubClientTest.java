package dev.prbot.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.github.GitHubClient.InlineComment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubClientTest {

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(TestProps.with(Provider.GEMINI), builder);
    }

    /** GitHub answers 404 for owner%2Frepo, so the slash in "owner/repo" must stay a slash. */
    @Test
    void fetchDiffKeepsTheSlashInTheRepoName() {
        server.expect(requestTo("https://api.github.com/repos/acme/widgets/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-github-token"))
                .andRespond(withSuccess("diff --git a/x b/x", MediaType.TEXT_PLAIN));

        assertThat(client.fetchDiff("acme/widgets", 7)).startsWith("diff --git");
        server.verify();
    }

    @Test
    void postReviewKeepsTheSlashInTheRepoName() {
        server.expect(requestTo("https://api.github.com/repos/acme/widgets/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commit_id").value("abc123"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andExpect(jsonPath("$.comments[0].path").value("A.java"))
                .andRespond(withSuccess());

        client.postReview(
                "acme/widgets",
                7,
                "abc123",
                "summary",
                List.of(new InlineComment("A.java", 3, "bug")));
        server.verify();
    }
}
