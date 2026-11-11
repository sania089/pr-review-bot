package dev.prbot.github;

import org.springframework.web.client.RestClient;

/** Base URL and API version shared by every GitHub REST client. */
final class GitHubHttp {

    static final String DEFAULT_URL = "https://api.github.com";

    private GitHubHttp() {}

    static RestClient client(RestClient.Builder builder, String baseUrl) {
        return builder.baseUrl(baseUrl).defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
    }
}
