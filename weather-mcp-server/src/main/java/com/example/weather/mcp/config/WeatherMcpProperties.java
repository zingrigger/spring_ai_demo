package com.example.weather.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment inputs that describe this MCP server as an OAuth 2.1 protected
 * resource (RFC 9728) and the authorization server that issues its tokens.
 *
 * <p>Tokens for {@link #requiredScope()} must be audience-bound to
 * {@link #resourceIdentifier()}; the auth-server does that in
 * {@code ResourceAudienceTokenCustomizer}.
 */
@ConfigurationProperties(prefix = "weather.mcp")
public record WeatherMcpProperties(String resourceBaseUrl, String authorizationServerUrl, String requiredScope) {

    static final String DEFAULT_RESOURCE_BASE_URL = "http://localhost:8081";
    static final String DEFAULT_AUTHORIZATION_SERVER_URL = "http://localhost:8083";
    static final String DEFAULT_REQUIRED_SCOPE = "weather:read";

    public WeatherMcpProperties {
        resourceBaseUrl = stripTrailingSlash(defaultIfBlank(resourceBaseUrl, DEFAULT_RESOURCE_BASE_URL));
        authorizationServerUrl = stripTrailingSlash(
                defaultIfBlank(authorizationServerUrl, DEFAULT_AUTHORIZATION_SERVER_URL));
        requiredScope = defaultIfBlank(requiredScope, DEFAULT_REQUIRED_SCOPE);
    }

    /**
     * The protected resource identifier: the Streamable HTTP MCP endpoint.
     */
    public String resourceIdentifier() {
        return this.resourceBaseUrl + "/mcp";
    }

    /**
     * The URL advertised in the {@code WWW-Authenticate} challenge, served by
     * {@code ProtectedResourceMetadataController}.
     */
    public String protectedResourceMetadataUrl() {
        return this.resourceBaseUrl + "/.well-known/oauth-protected-resource";
    }

    /**
     * The auth-server JWKS endpoint used to validate token signatures.
     */
    public String jwkSetUri() {
        return this.authorizationServerUrl + "/oauth2/jwks";
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
