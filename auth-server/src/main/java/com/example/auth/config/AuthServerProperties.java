package com.example.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * Externalized configuration for the auth-server ({@code auth.*}).
 *
 * <p>The issuer, the signing keystore and the token lifetimes are deployment
 * inputs; nothing here falls back to a generated value.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthServerProperties(String issuer, Keystore keystore, Oauth oauth, Resources resources, Admin admin) {

    public AuthServerProperties {
        if (resources == null) {
            resources = new Resources("http://localhost:8081/mcp");
        }
        if (admin == null) {
            admin = new Admin(List.of());
        }
    }

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

    /**
     * Resource identifiers that tokens are audience-bound to (RFC 9068). The
     * weather MCP server validates that {@code aud} matches its own resource
     * identifier.
     */
    public record Resources(String weatherMcp) {
    }

    /**
     * Platform administrator accounts allowed to call {@code /api/admin/**}.
     */
    public record Admin(List<String> accounts) {

        public Admin {
            accounts = accounts == null ? List.of()
                    : accounts.stream().filter(StringUtils::hasText).map(String::trim).toList();
        }
    }
}
