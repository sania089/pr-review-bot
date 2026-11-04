package dev.prbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.prbot.config.BotProperties;
import dev.prbot.http.Retrier;
import dev.prbot.review.ReviewResult;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/*
 * Calls the Anthropic Messages API. Output is forced into a JSON schema by requiring the model to
 * call the submit_review tool, so there is no free-text JSON to parse. Only created when
 * prbot.llm.provider=claude, so no Anthropic key is needed otherwise.
 */
@Component
@ConditionalOnProperty(prefix = "prbot.llm", name = "provider", havingValue = "claude")
public class ClaudeClient implements LlmClient {

    private static final Map<String, Object> TOOL =
            Map.of(
                    "name", "submit_review",
                    "description", "Submit the code review.",
                    "input_schema", ReviewPrompt.SCHEMA);

    private final RestClient http;
    private final String model;
    private final ObjectMapper mapper;
    private final Retrier retrier;

    public ClaudeClient(
            BotProperties props, ObjectMapper mapper, RestClient.Builder builder, Retrier retrier) {
        this.model = props.llm().claude().model();
        this.mapper = mapper;
        this.retrier = retrier;
        this.http =
                builder.baseUrl("https://api.anthropic.com")
                        .defaultHeader("x-api-key", props.llm().claude().apiKey())
                        .defaultHeader("anthropic-version", "2023-06-01")
                        .build();
    }

    @Override
    public ReviewResult review(String annotatedDiff) {
        Map<String, Object> request =
                Map.of(
                        "model",
                        model,
                        "max_tokens",
                        4096,
                        "system",
                        ReviewPrompt.SYSTEM + "Always answer by calling the submit_review tool.",
                        "tools",
                        List.of(TOOL),
                        "tool_choice",
                        Map.of("type", "tool", "name", "submit_review"),
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        ReviewPrompt.userMessage(annotatedDiff))));

        JsonNode response =
                retrier.call(
                        () ->
                                http.post()
                                        .uri("/v1/messages")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(request)
                                        .retrieve()
                                        .body(JsonNode.class));

        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                return ReviewPrompt.parse(mapper, block.path("input").toString());
            }
        }
        throw new IllegalStateException("Model did not call submit_review");
    }
}
