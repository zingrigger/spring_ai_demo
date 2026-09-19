package com.example.auth.token;

import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Customizes JWT claims. Client-credentials tokens carry only the client
 * identity and its granted scopes; user tokens get their organization context
 * added once the login flow binds it (Task 4/5).
 */
@Component
public class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        if (isClientCredentialsToken(context)) {
            return;
        }
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            // Organization-bound user claims (preferred_username, name, org_id, org_name,
            // roles) are populated from the server-side binding added with the login flow.
        }
    }

    private static boolean isClientCredentialsToken(JwtEncodingContext context) {
        return context.getPrincipal() instanceof OAuth2ClientAuthenticationToken;
    }
}
