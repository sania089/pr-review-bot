package dev.prbot.github;

import org.springframework.web.client.RestClient;

/** Base URL and API version shared by every GitHub REST client. */
final class GitHubHttp {

    private GitHubHttp() {}

    static RestClient client(RestClient.Builder builder) {
        return builder.baseUrl("https://api.github.com")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
