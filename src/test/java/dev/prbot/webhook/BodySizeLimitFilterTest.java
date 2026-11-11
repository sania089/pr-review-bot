package dev.prbot.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodySizeLimitFilterTest {

    private final BodySizeLimitFilter filter = new BodySizeLimitFilter(100);

    private int run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};
        FilterChain chain = (req, res) -> reached[0] = true;
        filter.doFilter(request, response, chain);
        return reached[0] ? 200 : response.getStatus();
    }

    private static MockHttpServletRequest post(String uri, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void letsASmallWebhookThrough() throws Exception {
        assertThat(run(post("/webhook/github", 100))).isEqualTo(200);
    }

    @Test
    void rejectsABodyOverTheLimit() throws Exception {
        assertThat(run(post("/webhook/github", 101))).isEqualTo(413);
    }

    @Test
    void rejectsAnUndeclaredLength() throws Exception {
        MockHttpServletRequest request = post("/webhook/github", 10);
        request.setContent(null); // no Content-Length, like a chunked upload
        assertThat(run(request)).isEqualTo(411);
    }

    @Test
    void ignoresOtherPaths() throws Exception {
        assertThat(run(post("/api/login", 5000))).isEqualTo(200);
    }
}
