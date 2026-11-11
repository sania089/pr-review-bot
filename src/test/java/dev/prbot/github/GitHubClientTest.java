package dev.prbot.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.prbot.TestProps;
import dev.prbot.github.GitHubClient.InlineComment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class GitHubClientTest {

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client =
                new GitHubClient(
                        id -> "test-github-token",
                        builder,
                        TestProps.retrier(),
                        GitHubHttp.DEFAULT_URL);
    }

    @Test
    void fetchDiffRetriesOn503ThenSucceeds() {
        String url = "https://api.github.com/repos/acme/widgets/pulls/7";
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(url))
                .andRespond(withSuccess("diff --git a/x b/x", MediaType.TEXT_PLAIN));

        assertThat(client.fetchDiff(1, "acme/widgets", 7)).startsWith("diff --git");
        server.verify();
    }

    @Test
    void fetchDiffDoesNotRetryOn404() {
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchDiff(1, "acme/widgets", 7))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
        server.verify();
    }

    @Test
    void postReviewRetriesOn429() {
        String url = "https://api.github.com/repos/acme/widgets/pulls/7/reviews";
        server.expect(ExpectedCount.once(), requestTo(url))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withSuccess());

        client.postReview(1, "acme/widgets", 7, "abc123", "summary", List.of());
        server.verify();
    }

    /** GitHub answers 404 for owner%2Frepo, so the slash in "owner/repo" must stay a slash. */
    @Test
    void fetchDiffKeepsTheSlashInTheRepoName() {
        server.expect(requestTo("https://api.github.com/repos/acme/widgets/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-github-token"))
                .andRespond(withSuccess("diff --git a/x b/x", MediaType.TEXT_PLAIN));

        assertThat(client.fetchDiff(1, "acme/widgets", 7)).startsWith("diff --git");
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
                1,
                "acme/widgets",
                7,
                "abc123",
                "summary",
                List.of(new InlineComment("A.java", 3, "bug")));
        server.verify();
    }

    @Test
    void fetchFileReadsRawContentAtTheGivenRef() {
        server.expect(
                        requestTo(
                                "https://api.github.com/repos/acme/widgets/contents/.prbot.yml?ref=main"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-github-token"))
                .andExpect(header("Accept", "application/vnd.github.raw+json"))
                .andRespond(withSuccess("strictness: low", MediaType.TEXT_PLAIN));

        assertThat(client.fetchFile(1, "acme/widgets", ".prbot.yml", "main"))
                .contains("strictness: low");
        server.verify();
    }

    @Test
    void fetchFileIsEmptyWhenTheFileDoesNotExist() {
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetchFile(1, "acme/widgets", ".prbot.yml", "main")).isEmpty();
        server.verify();
    }

    @Test
    void fetchFileOmitsTheRefWhenUnknown() {
        server.expect(requestTo("https://api.github.com/repos/acme/widgets/contents/.prbot.yml"))
                .andRespond(withSuccess("a: b", MediaType.TEXT_PLAIN));

        assertThat(client.fetchFile(1, "acme/widgets", ".prbot.yml", "")).isPresent();
        server.verify();
    }

    @Test
    void fetchFileStillFailsOnServerErrorsAfterRetries() {
        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchFile(1, "acme/widgets", ".prbot.yml", "main"))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
    }
}
