package com.example.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized configuration for the auth-server ({@code auth.*}).
 *
 * <p>The issuer, the signing keystore and the token lifetimes are deployment
 * inputs; nothing here falls back to a generated value.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthServerProperties(String issuer, Keystore keystore, Oauth oauth) {

    /**
     * External PKCS#12/JKS keystore holding the RSA key that signs tokens.
     */
    public record Keystore(String location, String password, String type, String alias) {
    }

    /**
     * OAuth 2.1 / OIDC lifetimes; the defaults in {@code application.yml} mirror the
     * design spec (code 5m, access 10m, id 10m, refresh 30d, session idle 30m).
     */
    public record Oauth(Duration authorizationCodeTtl, Duration accessTokenTtl, Duration idTokenTtl,
                        Duration refreshTokenTtl, Duration sessionIdleTimeout) {
    }
}
