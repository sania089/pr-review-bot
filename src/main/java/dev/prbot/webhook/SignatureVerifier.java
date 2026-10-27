package dev.prbot.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import dev.prbot.config.BotProperties;

/** Validates GitHub's X-Hub-Signature-256 header against the raw request body. */
@Component
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";
    private final byte[] secret;

    public SignatureVerifier(BotProperties props) {
        this.secret = props.webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] body, String header) {
        if (secret.length == 0 || header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(header.substring(PREFIX.length()));
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
