package dev.prbot.webhook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties.Provider;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final String SECRET = TestProps.WEBHOOK_SECRET;
    private final SignatureVerifier verifier =
            new SignatureVerifier(TestProps.with(Provider.GEMINI));

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void acceptsCorrectSignature() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertTrue(verifier.isValid(body, sign(body, SECRET)));
    }

    @Test
    void rejectsWrongSecretTamperedBodyAndMissingHeader() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertFalse(verifier.isValid(body, sign(body, "other")));
        assertFalse(
                verifier.isValid("{\"a\":2}".getBytes(StandardCharsets.UTF_8), sign(body, SECRET)));
        assertFalse(verifier.isValid(body, null));
        assertFalse(verifier.isValid(body, "sha256=zzzz"));
    }
}
