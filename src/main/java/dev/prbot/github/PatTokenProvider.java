package dev.prbot.github;

import dev.prbot.config.BotProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local development: one personal access token for every repository. */
@Component
@ConditionalOnProperty(
        prefix = "prbot.github",
        name = "auth",
        havingValue = "pat",
        matchIfMissing = true)
class PatTokenProvider implements TokenProvider {

    private final String token;

    PatTokenProvider(BotProperties props) {
        this.token = props.github().token();
    }

    @Override
    public String tokenFor(long installationId) {
        return token;
    }
}
