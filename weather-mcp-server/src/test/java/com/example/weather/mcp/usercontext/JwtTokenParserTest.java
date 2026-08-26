package com.example.weather.mcp.usercontext;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenParserTest {

    private static final String SECRET = "demo-secret-change-me-0123456789abcdef";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtTokenParser parser = new JwtTokenParser(SECRET);

    private static String tokenWithClaims(Instant expiration) {
        return Jwts.builder()
                .claim("userId", "1001")
                .claim("tenantId", "acme")
                .expiration(Date.from(expiration))
                .signWith(KEY)
                .compact();
    }

    @Test
    void parsesUserIdAndTenantIdFromValidToken() {
        Optional<UserContext> context = parser.parse(tokenWithClaims(Instant.now().plusSeconds(3600)));
        assertThat(context).hasValue(new UserContext("1001", "acme"));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-another-secret-0123456789".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder().claim("userId", "1001").signWith(otherKey).compact();
        assertThat(parser.parse(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        assertThat(parser.parse(tokenWithClaims(Instant.now().minusSeconds(60)))).isEmpty();
    }

    @Test
    void returnsEmptyForMalformedToken() {
        assertThat(parser.parse("not-a-jwt")).isEmpty();
    }

    @Test
    void allowsMissingClaims() {
        // jjwt 0.12.x omits the payload entirely when a token has no claims,
        // producing a degenerate "header..signature" token that jjwt itself
        // rejects as malformed (UnsupportedJwtException). Use a benign
        // non-user-context claim so the token is structurally valid while
        // still missing userId/tenantId.
        String token = Jwts.builder().claim("scope", "read").signWith(KEY).compact();
        assertThat(parser.parse(token)).hasValue(new UserContext(null, null));
    }
}
