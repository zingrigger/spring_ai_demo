package com.example.auth.token;

import com.example.auth.config.AuthServerProperties;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds access tokens that carry the {@code weather:read} scope to the weather
 * MCP resource.
 *
 * <p>Spring Authorization Server defaults {@code aud} to the client id, which
 * a resource server must not accept as proof that the token was issued for it
 * (RFC 9068). Tokens for other clients or scopes keep the default audience.
 */
@Component
public class ResourceAudienceTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    static final String WEATHER_READ_SCOPE = "weather:read";

    private final AuthServerProperties properties;

    public ResourceAudienceTokenCustomizer(AuthServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                || !context.getAuthorizedScopes().contains(WEATHER_READ_SCOPE)) {
            return;
        }
        // ArrayList (not List.of): the JDBC authorization service serializes
        // claims with default typing, and its type validator only allows the
        // JDK collection types on Spring Security's allow list.
        context.getClaims().audience(new ArrayList<>(List.of(this.properties.resources().weatherMcp())));
    }
}
