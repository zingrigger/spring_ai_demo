package com.example.weather.mcp.usercontext;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public final class JwtTokenParser {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenParser.class);

    private final SecretKey secretKey;

    public JwtTokenParser(@Value("${user-context.jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<UserContext> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(secretKey).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new UserContext(
                    claims.get("userId", String.class),
                    claims.get("tenantId", String.class)));
        } catch (JwtException | IllegalArgumentException error) {
            logger.warn("JWT 解析失败: {}", error.getMessage());
            return Optional.empty();
        }
    }
}
