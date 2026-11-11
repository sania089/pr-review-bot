package dev.prbot.api;

import dev.prbot.config.BotProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Session login for the React app. One admin credential from the environment, no user table. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final BotProperties.Admin admin;
    private final SecurityContextRepository contexts;

    public AuthController(BotProperties props, SecurityContextRepository contexts) {
        this.admin = props.admin();
        this.contexts = contexts;
    }

    public record LoginRequest(String username, String password) {}

    public record Me(String username) {}

    @PostMapping("/login")
    public Me login(
            @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (body.username() == null
                || body.password() == null
                || !credentialsMatch(body.username(), body.password())) {
            log.warn("Rejected dashboard login attempt");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        if (request.getSession(false) != null) {
            request.changeSessionId(); // never keep a session id that existed before login
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        admin.username(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return new Me(admin.username());
    }

    /** The React route guard calls this to find out whether the session is still valid. */
    @GetMapping("/me")
    public Me me(Principal principal) {
        return new Me(principal.getName());
    }

    /** Compares digests in constant time and always checks both fields. */
    private boolean credentialsMatch(String username, String password) {
        boolean userOk = sameText(username, admin.username());
        boolean passwordOk = sameText(password, admin.password());
        return userOk & passwordOk;
    }

    private static boolean sameText(String a, String b) {
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
