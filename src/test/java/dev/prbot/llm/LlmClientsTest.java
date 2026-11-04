package dev.prbot.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.prbot.TestProps;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.review.ReviewResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Request shape and response parsing for each provider, with the HTTP layer mocked. */
class LlmClientsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REVIEW_JSON =
            """
            {"summary":"Looks risky.","findings":[
              {"path":"A.java","line":3,"severity":"high","body":"NPE"}]}
            """;

    private static void assertParsed(ReviewResult result) {
        assertThat(result.summary()).isEqualTo("Looks risky.");
        assertThat(result.findings())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.path()).isEqualTo("A.java");
                            assertThat(f.line()).isEqualTo(3);
                            assertThat(f.severity()).isEqualTo("high");
                        });
    }

    @Test
    void geminiSendsSchemaAndParsesJsonText() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiClient client =
                new GeminiClient(
                        TestProps.with(Provider.GEMINI), MAPPER, builder, TestProps.retrier());

        String body =
                MAPPER.writeValueAsString(
                        java.util.Map.of(
                                "candidates",
                                java.util.List.of(
                                        java.util.Map.of(
                                                "content",
                                                java.util.Map.of(
                                                        "parts",
                                                        java.util.List.of(
                                                                java.util.Map.of(
                                                                        "thought",
                                                                        true,
                                                                        "text",
                                                                        "thinking..."),
                                                                java.util.Map.of(
                                                                        "text", REVIEW_JSON)))))));
        server.expect(
                        requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andExpect(header("x-goog-api-key", "test-gemini-key"))
                .andExpect(
                        jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(
                        jsonPath("$.generationConfig.responseJsonSchema.required[0]")
                                .value("summary"))
                .andExpect(
                        jsonPath("$.contents[0].parts[0].text")
                                .value(org.hamcrest.Matchers.containsString("<diff>")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertParsed(client.review("L1: code"));
        server.verify();
    }

    @Test
    void geminiFailsClearlyWhenNothingIsReturned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiClient client =
                new GeminiClient(
                        TestProps.with(Provider.GEMINI), MAPPER, builder, TestProps.retrier());
        server.expect(
                        requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(
                        withSuccess(
                                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.review("x")).hasMessageContaining("SAFETY");
    }

    @Test
    void groqRetriesAfter429ThenParses() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroqClient client =
                new GroqClient(TestProps.with(Provider.GROQ), MAPPER, builder, TestProps.retrier());
        String url = "https://api.groq.com/openai/v1/chat/completions";
        String ok =
                MAPPER.writeValueAsString(
                        java.util.Map.of(
                                "choices",
                                java.util.List.of(
                                        java.util.Map.of(
                                                "message",
                                                java.util.Map.of("content", REVIEW_JSON)))));
        server.expect(ExpectedCount.once(), requestTo(url))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(ExpectedCount.once(), requestTo(url))
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));

        assertParsed(client.review("x"));
        server.verify();
    }

    @Test
    void groqGivesUpAfterThreeFailedAttempts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroqClient client =
                new GroqClient(TestProps.with(Provider.GROQ), MAPPER, builder, TestProps.retrier());
        server.expect(
                        ExpectedCount.times(3),
                        requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.review("x")).isInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void groqSendsStrictSchemaAndParsesMessageContent() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroqClient client =
                new GroqClient(TestProps.with(Provider.GROQ), MAPPER, builder, TestProps.retrier());

        String body =
                MAPPER.writeValueAsString(
                        java.util.Map.of(
                                "choices",
                                java.util.List.of(
                                        java.util.Map.of(
                                                "finish_reason",
                                                "stop",
                                                "message",
                                                java.util.Map.of("content", REVIEW_JSON)))));
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-groq-key"))
                .andExpect(jsonPath("$.model").value("groq-test"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertParsed(client.review("L1: code"));
        server.verify();
    }

    @Test
    void groqRejectsMalformedJsonFromTheModel() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroqClient client =
                new GroqClient(TestProps.with(Provider.GROQ), MAPPER, builder, TestProps.retrier());
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(
                        withSuccess(
                                "{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}",
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.review("x")).hasMessageContaining("malformed");
    }

    @Test
    void claudeForcesToolCallAndParsesToolInput() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeClient client =
                new ClaudeClient(
                        TestProps.with(Provider.CLAUDE), MAPPER, builder, TestProps.retrier());

        String body = "{\"content\":[{\"type\":\"tool_use\",\"input\":" + REVIEW_JSON + "}]}";
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(header("x-api-key", "test-claude-key"))
                .andExpect(jsonPath("$.tool_choice.name").value("submit_review"))
                .andExpect(jsonPath("$.tools[0].input_schema.required[0]").value("summary"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertParsed(client.review("L1: code"));
        server.verify();
    }
}
