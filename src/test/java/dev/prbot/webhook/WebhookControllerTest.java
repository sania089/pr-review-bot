package dev.prbot.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.prbot.TestProps;
import dev.prbot.config.BotProperties;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.persistence.ReviewRepository;
import dev.prbot.review.ReviewService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebhookControllerTest {

    private static final String PR_OPENED =
            """
            {"action":"opened",
             "pull_request":{"number":7,"draft":false,"head":{"sha":"abc123"}},
             "repository":{"full_name":"acme/widgets"},
             "installation":{"id":99}}
            """;

    private static final long ID = 5;

    private ReviewService reviews;
    private ReviewRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reviews = mock(ReviewService.class);
        repository = mock(ReviewRepository.class);
        when(repository.enqueue(any(), anyInt(), any(), any(), any())).thenReturn(Optional.of(ID));
        BotProperties props = TestProps.with(Provider.GEMINI);
        WebhookController controller =
                new WebhookController(
                        new SignatureVerifier(props),
                        reviews,
                        repository,
                        props,
                        new ObjectMapper());
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new WebhookExceptionHandler())
                        .build();
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec(
                        TestProps.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256="
                + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static MockHttpServletRequestBuilder signed(String event, String body)
            throws Exception {
        return post("/webhook/github")
                .header("X-GitHub-Event", event)
                .header("X-Hub-Signature-256", sign(body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void rejectsBadSignature() throws Exception {
        mvc.perform(
                        post("/webhook/github")
                                .header("X-GitHub-Event", "pull_request")
                                .header("X-Hub-Signature-256", "sha256=00")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PR_OPENED))
                .andExpect(status().isUnauthorized());
        verify(reviews, never())
                .reviewAsync(anyLong(), anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void answersPing() throws Exception {
        mvc.perform(signed("ping", "{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void queuesReviewForOpenedPullRequest() throws Exception {
        mvc.perform(signed("pull_request", PR_OPENED)).andExpect(status().isAccepted());
        verify(reviews).reviewAsync(ID, 99, "acme/widgets", 7, "abc123");
    }

    @Test
    void duplicateDeliveryIsNotReviewedAgain() throws Exception {
        when(repository.enqueue(any(), anyInt(), any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(signed("pull_request", PR_OPENED))
                .andExpect(status().isOk())
                .andExpect(content().string("duplicate"));
        verify(reviews, never())
                .reviewAsync(anyLong(), anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void ignoresDraftPullRequests() throws Exception {
        mvc.perform(signed("pull_request", PR_OPENED.replace("\"draft\":false", "\"draft\":true")))
                .andExpect(status().isAccepted());
        verify(reviews, never())
                .reviewAsync(anyLong(), anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void ignoresPullRequestsOpenedByBots() throws Exception {
        for (String user :
                List.of(
                        "{\"login\":\"dependabot[bot]\",\"type\":\"Bot\"}",
                        "{\"login\":\"renovate[bot]\"}")) {
            String body = PR_OPENED.replace("\"draft\":false", "\"draft\":false,\"user\":" + user);
            mvc.perform(signed("pull_request", body)).andExpect(status().isAccepted());
        }
        verify(reviews, never())
                .reviewAsync(anyLong(), anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void stillReviewsPullRequestsFromHumans() throws Exception {
        String body =
                PR_OPENED.replace(
                        "\"draft\":false",
                        "\"draft\":false,\"user\":{\"login\":\"sania089\",\"type\":\"User\"}");
        mvc.perform(signed("pull_request", body)).andExpect(status().isAccepted());
        verify(reviews).reviewAsync(ID, 99, "acme/widgets", 7, "abc123");
    }

    @Test
    void returns400ForMalformedJsonWithoutEchoingIt() throws Exception {
        String body = "{not json secret-looking-value";
        mvc.perform(signed("pull_request", body))
                .andExpect(status().isBadRequest())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "secret-looking"))));
    }

    @Test
    void returns503WhenReviewQueueIsFull() throws Exception {
        doThrow(new TaskRejectedException("full"))
                .when(reviews)
                .reviewAsync(anyLong(), anyLong(), any(), anyInt(), any());

        mvc.perform(signed("pull_request", PR_OPENED))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));
        verify(repository).markSkipped(ID, "review queue is full");
    }

    @Test
    void returns500WithGenericBodyOnUnexpectedError() throws Exception {
        doThrow(new IllegalStateException("db password is hunter2"))
                .when(reviews)
                .reviewAsync(anyLong(), anyLong(), any(), anyInt(), any());

        mvc.perform(signed("pull_request", PR_OPENED))
                .andExpect(status().isInternalServerError())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("hunter2"))));
    }
}
