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

/** Google Gemini generateContent API; structured output via responseJsonSchema. */
@Component
@ConditionalOnProperty(prefix = "prbot.llm", name = "provider", havingValue = "gemini")
public class GeminiClient implements LlmClient {

    private final RestClient http;
    private final String model;
    private final ObjectMapper mapper;
    private final Retrier retrier;

    public GeminiClient(
            BotProperties props, ObjectMapper mapper, RestClient.Builder builder, Retrier retrier) {
        this.model = props.llm().gemini().model();
        this.mapper = mapper;
        this.retrier = retrier;
        this.http =
                builder.baseUrl("https://generativelanguage.googleapis.com")
                        .defaultHeader("x-goog-api-key", props.llm().gemini().apiKey())
                        .build();
    }

    @Override
    public ReviewResult review(String annotatedDiff) {
        Map<String, Object> request =
                Map.of(
                        "systemInstruction",
                                Map.of("parts", List.of(Map.of("text", ReviewPrompt.SYSTEM))),
                        "contents",
                                List.of(
                                        Map.of(
                                                "role",
                                                "user",
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "text",
                                                                ReviewPrompt.userMessage(
                                                                        annotatedDiff))))),
                        "generationConfig",
                                Map.of(
                                        "responseMimeType",
                                        "application/json",
                                        "responseJsonSchema",
                                        ReviewPrompt.SCHEMA,
                                        "temperature",
                                        0.2));

        JsonNode response =
                retrier.call(
                        () ->
                                http.post()
                                        .uri("/v1beta/models/{model}:generateContent", model)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(request)
                                        .retrieve()
                                        .body(JsonNode.class));

        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) {
                text.append(part.path("text").asText(""));
            }
        }
        if (text.isEmpty()) {
            String reason =
                    response.path("candidates").path(0).path("finishReason").asText("unknown");
            throw new IllegalStateException(
                    "Gemini returned no content (finishReason=" + reason + ")");
        }
        return ReviewPrompt.parse(mapper, text.toString());
    }
}
