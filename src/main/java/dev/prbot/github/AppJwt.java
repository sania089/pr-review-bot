package dev.prbot.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Pure helpers for the GitHub App's RS256 JSON Web Token; no library needed. */
final class AppJwt {

    /** GitHub allows at most 10 minutes. 
    static final Duration LIFETIME = Duration.ofMinutes(9);

    /** GitHub recommends backdating iat to tolerate clock drift. */
    static final Duration BACKDATE = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER =
            B64.encodeToString(
                    "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private AppJwt() {}

    static String create(String appId, PrivateKey key, Instant now) {
        try {
            String claims =
                    JSON.writeValueAsString(
                            Map.of(
                                    "iss", appId,
                                    "iat", now.minus(BACKDATE).getEpochSecond(),
                                    "exp", now.plus(LIFETIME).getEpochSecond()));
            String signingInput =
                    HEADER + "." + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(key);
            rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(rsa.sign());
        } catch (JsonProcessingException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign the GitHub App JWT", e);
        }
    }

    /**
     * Parses a PEM private key. GitHub hands out PKCS#1 ("BEGIN RSA PRIVATE KEY") which the JDK
     * cannot read directly, so it is wrapped into PKCS#8 first. Literal "\n" sequences are turned
     * into newlines because env vars often carry the PEM on one line.
     */
    static PrivateKey parsePrivateKey(String pem) {
        String text = pem.replace("\\n", "\n");
        boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
        try {
            byte[] der = Base64.getMimeDecoder().decode(text.replaceAll("-----[A-Z ]+-----", ""));
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The cause is not chained: its message could echo key material.
            throw new IllegalStateException(
                    "prbot.github.app-private-key is not a valid RSA PEM key");
        }
    }

    /**
     * PrivateKeyInfo ::= SEQUENCE { version 0, rsaEncryption AlgorithmIdentifier, OCTET STRING }.
     */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] rsaAlgorithm = {
            0x30,
            0x0d,
            0x06,
            0x09,
            0x2a,
            (byte) 0x86,
            0x48,
            (byte) 0x86,
            (byte) 0xf7,
            0x0d,
            0x01,
            0x01,
            0x01,
            0x05,
            0x00
        };
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(version);
        body.writeBytes(rsaAlgorithm);
        body.writeBytes(der(0x04, pkcs1));
        return der(0x30, body.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = content.length;
        if (n < 128) {
            out.write(n);
        } else {
            int lengthBytes = (32 - Integer.numberOfLeadingZeros(n) + 7) / 8;
            out.write(0x80 | lengthBytes);
            for (int i = lengthBytes - 1; i >= 0; i--) {
                out.write(n >>> (8 * i));
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }
}
