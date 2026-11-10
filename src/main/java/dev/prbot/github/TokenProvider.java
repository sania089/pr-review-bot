package dev.prbot.github;

/** Supplies the bearer token for GitHub API calls made on behalf of one App installation. */
@FunctionalInterface
public interface TokenProvider {

    /**
     * @param installationId from the webhook payload; ignored by the PAT implementation
     */
    String tokenFor(long installationId);
}
