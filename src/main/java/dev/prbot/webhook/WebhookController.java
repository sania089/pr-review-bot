package dev.prbot.webhook;

import java.io.IOException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.prbot.review.ReviewService;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> HANDLED_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final SignatureVerifier verifier;
    private final ReviewService reviews;
    private final ObjectMapper mapper;

    public WebhookController(SignatureVerifier verifier, ReviewService reviews, ObjectMapper mapper) {
        this.verifier = verifier;
        this.reviews = reviews;
        this.mapper = mapper;
    }

    @PostMapping("/webhook/github")
    public ResponseEntity<String> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "") String event) throws IOException {

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

        String repo = payload.path("repository").path("full_name").asText();
        int number = pr.path("number").asInt();
        String headSha = pr.path("head").path("sha").asText();
        log.info("PR event: {}#{} action={} sha={}", repo, number, action, headSha);

        reviews.reviewAsync(repo, number, headSha);
        return ResponseEntity.accepted().body("review queued");
    }
}
