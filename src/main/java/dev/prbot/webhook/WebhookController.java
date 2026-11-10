package dev.prbot.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.prbot.config.BotProperties;
import dev.prbot.persistence.ReviewRepository;
import dev.prbot.review.ReviewService;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> HANDLED_ACTIONS =
            Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final SignatureVerifier verifier;
    private final ReviewService reviews;
    private final ReviewRepository repository;
    private final BotProperties props;
    private final ObjectMapper mapper;

    public WebhookController(
            SignatureVerifier verifier,
            ReviewService reviews,
            ReviewRepository repository,
            BotProperties props,
            ObjectMapper mapper) {
        this.verifier = verifier;
        this.reviews = reviews;
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
    }

    @PostMapping("/webhook/github")
    public ResponseEntity<String> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "") String event)
            throws IOException {

        if (!verifier.isValid(body, signature)) {
            return ResponseEntity.status(401).body("invalid signature");
        }
        if (event.equals("ping")) {
            return ResponseEntity.ok("pong");
        }
        if (!event.equals("pull_request")) {
            return ResponseEntity.accepted().body("ignored event");
        }

        JsonNode payload = mapper.readTree(body);
        String action = payload.path("action").asText();
        JsonNode pr = payload.path("pull_request");
        if (!HANDLED_ACTIONS.contains(action) || pr.path("draft").asBoolean(false)) {
            return ResponseEntity.accepted().body("ignored action");
        }
        if (isBotAuthor(pr)) {
            return ResponseEntity.accepted().body("ignored bot author");
        }

        String repo = payload.path("repository").path("full_name").asText();
        int number = pr.path("number").asInt();
        long installationId = payload.path("installation").path("id").asLong(0);
        String headSha = pr.path("head").path("sha").asText();
        String baseRef = pr.path("base").path("ref").asText();
        log.info("PR event: {}#{} action={} sha={}", repo, number, action, headSha);

        Optional<Long> id =
                repository.enqueue(
                        repo,
                        number,
                        headSha,
                        props.llm().provider().name(),
                        props.llm().selected().model());
        if (id.isEmpty()) {
            log.info(
                    "Duplicate delivery for {}#{} sha={}, already reviewed", repo, number, headSha);
            return ResponseEntity.ok("duplicate");
        }

        try {
            reviews.reviewAsync(id.get(), installationId, repo, number, headSha, baseRef);
        } catch (TaskRejectedException e) {
            repository.markSkipped(id.get(), "review queue is full");
            log.warn("SKIPPED {}#{} sha={}: review queue is full", repo, number, headSha);
            return ResponseEntity.status(503).header("Retry-After", "60").body("review queue full");
        }
        return ResponseEntity.accepted().body("review queued");
    }

    /** Dependabot, Renovate and our own reviews are bot accounts; reviewing them is noise. */
    private static boolean isBotAuthor(JsonNode pr) {
        JsonNode user = pr.path("user");
        return "Bot".equals(user.path("type").asText())
                || user.path("login").asText().endsWith("[bot]");
    }
}
