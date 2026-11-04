package dev.prbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String deliveryHeader, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (deliveryHeader != null) {
            request.addHeader("X-GitHub-Delivery", deliveryHeader);
        }
        AtomicReference<String> inside = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req,
                            jakarta.servlet.ServletResponse res) {
                        inside.set(MDC.get(RequestIdFilter.MDC_KEY));
                    }
                });
        return inside.get();
    }

    @Test
    void reusesGithubDeliveryId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String id = run("72d3162e-cc78-11e3-81ab-4c9367dc0958", response);

        assertThat(id).isEqualTo("72d3162e-cc78-11e3-81ab-4c9367dc0958");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesAnIdWhenHeaderIsMissing() throws Exception {
        assertThat(run(null, new MockHttpServletResponse())).hasSize(36);
    }

    @Test
    void replacesUnsafeHeaderValues() throws Exception {
        String id = run("evil\nINFO forged log line", new MockHttpServletResponse());

        assertThat(id).doesNotContain("evil").hasSize(36);
    }
}
