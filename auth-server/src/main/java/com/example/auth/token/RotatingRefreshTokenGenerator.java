package com.example.auth.token;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues refresh tokens for the PKCE browser client.
 *
 * <p>Spring Authorization Server's own refresh token generator refuses public
 * clients in the authorization-code grant. The design requires rotating refresh
 * tokens for the browser client, so this generator keeps the framework's key
 * format and configured lifetime but drops that restriction. Rotation stays
 * enforced by the client's {@code reuse-refresh-tokens=false} setting and by
 * the refresh grant re-checking the organization relationship.
 */
public final class RotatingRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private static final int KEY_BYTES = 96;

    private final StringKeyGenerator keyGenerator =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), KEY_BYTES);
    private final Clock clock = Clock.systemUTC();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        Instant issuedAt = this.clock.instant();
        Duration timeToLive = context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive();
        return new OAuth2RefreshToken(this.keyGenerator.generateKey(), issuedAt, issuedAt.plus(timeToLive));
    }
}
