package dev.prbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.prbot.config.BotProperties;
import dev.prbot.http.Retrier;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Groq's OpenAI-compatible chat completions API; structured output via response_format json_schema.
 */
@Component
@ConditionalOnProperty(prefix = "prbot.llm", name = "provider", havingValue = "groq")
public class GroqClient implements LlmClient {

    private final RestClient http;
    private final String model;
    private final ObjectMapper mapper;
    private final Retrier retrier;

    public GroqClient(
            BotProperties props, ObjectMapper mapper, RestClient.Builder builder, Retrier retrier) {
        this.model = props.llm().groq().model();
        this.mapper = mapper;
        this.retrier = retrier;
        this.http =
                builder.baseUrl("https://api.groq.com/openai/v1")
                        .defaultHeader("Authorization", "Bearer " + props.llm().groq().apiKey())
                        .build();
    }

    @Override
    public LlmResponse review(String annotatedDiff) {
        Map<String, Object> request =
                Map.of(
                        "model",
                        model,
                        "temperature",
                        0.2,
                        "messages",
                        List.of(
                                Map.of("role", "system", "content", ReviewPrompt.SYSTEM),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        ReviewPrompt.userMessage(annotatedDiff))),
                        "response_format",
                        Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of(
                                        "name",
                                        "submit_review",
                                        "strict",
                                        true,
                                        "schema",
                                        ReviewPrompt.SCHEMA)));

        JsonNode response =
                retrier.call(
                        () ->
                                http.post()
                                        .uri("/chat/completions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(request)
                                        .retrieve()
                                        .body(JsonNode.class));

        JsonNode choice = response.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            String reason = choice.path("finish_reason").asText("unknown");
            throw new IllegalStateException(
                    "Groq returned no content (finish_reason=" + reason + ")");
        }
        JsonNode usage = response.path("usage");
        return new LlmResponse(
                ReviewPrompt.parse(mapper, content),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }
}
