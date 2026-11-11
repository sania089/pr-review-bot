package dev.prbot.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaForwardControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test
    void clientRoutesAreForwardedToIndexHtml() throws Exception {
        for (String path : new String[] {"/", "/docs", "/login", "/dashboard", "/reviews/42"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void unknownPathsAreNotForwarded() throws Exception {
        mvc.perform(get("/api/nothing")).andExpect(status().isNotFound());
    }
}
