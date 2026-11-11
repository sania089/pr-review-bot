package dev.prbot.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The React app uses client-side routes. A refresh or a pasted link such as {@code /reviews/1} hits
 * Spring first, so these paths are forwarded to {@code index.html} and React Router takes over.
 * Keep the list in step with the routes in {@code frontend/src/App.tsx}.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/docs", "/login", "/dashboard", "/reviews/{id}"})
    String forwardToIndex() {
        return "forward:/index.html";
    }
}
