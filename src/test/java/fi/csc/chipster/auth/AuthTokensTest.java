package fi.csc.chipster.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthTokens;
import io.jsonwebtoken.Jwts;

/**
 * The maximum lifetime of a user token must be counted from the login time,
 * also when the token is refreshed
 */
public class AuthTokensTest {

    private static final KeyPair KEY_PAIR = Jwts.SIG.ES512.keyPair().build();

    /**
     * Create a token and read its expiration directly from the payload, because
     * jjwt refuses to decode expired tokens
     */
    private static Instant getExpiration(Instant loginTime, String role) {
        String jws = AuthTokens.createUserToken("jaas/test", Set.of(role), loginTime, KEY_PAIR.getPrivate(),
                Jwts.SIG.ES512, "Test");

        String payload = new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"exp\":(\\d+)").matcher(payload);
        assertTrue(matcher.find(), payload);

        return Instant.ofEpochSecond(Long.parseLong(matcher.group(1)));
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private static void assertAround(Instant expected, Instant actual) {
        assertTrue(Duration.between(expected, actual).abs().toMinutes() < 1,
                "expected " + expected + ", got " + actual);
    }

    @Test
    public void newLogin() {
        assertAround(Instant.now().plus(Duration.ofDays(3)), getExpiration(Instant.now(), Role.CLIENT));
        assertAround(Instant.now().plus(Duration.ofHours(6)), getExpiration(Instant.now(), Role.SERVER));
    }

    @Test
    public void nearMaxLifetime() {
        // less than the normal lifetime left of the maximum lifetime of 10 days
        Instant clientLogin = daysAgo(9); // 1 day left, normal lifetime 3 days
        assertAround(clientLogin.plus(Duration.ofDays(10)), getExpiration(clientLogin, Role.CLIENT));

        Instant serverLogin = daysAgo(10).plus(Duration.ofHours(2)); // 2 hours left, normal lifetime 6 hours
        assertAround(serverLogin.plus(Duration.ofDays(10)), getExpiration(serverLogin, Role.SERVER));
    }

    @Test
    public void overMaxLifetime() {
        assertTrue(getExpiration(daysAgo(11), Role.CLIENT).isBefore(Instant.now()));
        assertTrue(getExpiration(daysAgo(11), Role.SERVER).isBefore(Instant.now()));
    }
}
