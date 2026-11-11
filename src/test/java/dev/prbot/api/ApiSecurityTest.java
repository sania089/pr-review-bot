package dev.prbot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.config.SecurityConfig;
import dev.prbot.persistence.ReviewRepository;
import dev.prbot.persistence.StatsRepository;
import dev.prbot.persistence.StatsWindow;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The real security filter chain in front of the API controllers. */
@WebMvcTest({ReviewsController.class, AuthController.class})
@Import({SecurityConfig.class, ApiSecurityTest.Props.class})
class ApiSecurityTest {

    @TestConfiguration
    static class Props {
        @Bean
        BotProperties botProperties() {
            return TestProps.with(Provider.GEMINI);
        }
    }

    @Autowired MockMvc mvc;
    @MockBean ReviewRepository reviews;
    @MockBean StatsRepository stats;

    private static String login(String user, String password) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}";
    }

    private MockHttpSession loggedInSession() throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(login("admin", TestProps.ADMIN_PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void apiRequiresALoginAndAnswersWith401NotARedirect() throws Exception {
        mvc.perform(get("/api/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reviews/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginSetsAHardenedSessionAndUnlocksTheApi() throws Exception {
        when(reviews.count(null, null)).thenReturn(0L);
        when(reviews.findPage(null, null, 20, 0)).thenReturn(List.of());
        when(stats.window(7)).thenReturn(new StatsWindow(7, 0, 0, 0, 0, 0, 0, null, null, 0, 0));
        when(stats.window(30)).thenReturn(new StatsWindow(30, 0, 0, 0, 0, 0, 0, null, null, 0, 0));

        MvcResult login =
                mvc.perform(
                                post("/api/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(login("admin", TestProps.ADMIN_PASSWORD)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.username").value("admin"))
                        .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/reviews").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/stats").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void wrongOrMissingCredentialsAreRejectedWithoutASession() throws Exception {
        for (String body :
                List.of(
                        login("admin", "wrong-password"),
                        login("someone", TestProps.ADMIN_PASSWORD),
                        "{\"username\":\"admin\"}",
                        "{}")) {
            MvcResult result =
                    mvc.perform(
                                    post("/api/login")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body))
                            .andExpect(status().isUnauthorized())
                            .andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutNeedsACsrfTokenAndEndsTheSession() throws Exception {
        MockHttpSession session = loggedInSession();

        mvc.perform(post("/api/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());

        mvc.perform(post("/api/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCookieFromBrowserIsAcceptedAsAnAuthenticatedRequest() throws Exception {
        MockHttpSession session = loggedInSession();
        Cookie cookie = new Cookie("JSESSIONID", session.getId());

        mvc.perform(get("/api/me").cookie(cookie).session(session)).andExpect(status().isOk());
    }

    @Test
    void healthAndStaticPathsStayPublic() throws Exception {
        // No controller is mapped here, so 404 (not 401) proves the security layer let it through.
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void webhookIsNotBehindTheLoginOrCsrf() throws Exception {
        // No webhook controller in this slice: 404 means it passed security; 401/403 would not.
        mvc.perform(post("/webhook/github").content("{}")).andExpect(status().isNotFound());
    }
}
