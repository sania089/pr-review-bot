package dev.prbot.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AppJwtTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static KeyPair pair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        pair = gen.generateKeyPair();
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN "
                + label
                + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END "
                + label
                + "-----\n";
    }

    @Test
    void createsAnRs256TokenWithGitHubsClaims() throws Exception {
        String jwt = AppJwt.create("12345", pair.getPrivate(), NOW);

        String[] parts = jwt.split("\\.");
        ObjectMapper json = new ObjectMapper();
        JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(claims.path("iss").asText()).isEqualTo("12345");
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond() - 60);
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 9 * 60);

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(pair.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void parsesPkcs8Pem() {
        String pem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());

        assertThat(AppJwt.parsePrivateKey(pem).getEncoded())
                .isEqualTo(pair.getPrivate().getEncoded());
    }

    /**
     * GitHub downloads PKCS#1; the 26-byte PKCS#8 prefix of a 2048-bit key is stripped to get one.
     */
    @Test
    void parsesPkcs1PemAndEnvStyleEscapedNewlines() {
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        String onOneLine = pem("RSA PRIVATE KEY", pkcs1).replace("\n", "\\n");

        assertThat(AppJwt.parsePrivateKey(onOneLine).getEncoded()).isEqualTo(pkcs8);
    }

    @Test
    void rejectsGarbageWithoutEchoingIt() {
        assertThatThrownBy(() -> AppJwt.parsePrivateKey("top-secret-garbage"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("top-secret-garbage");
    }
}
