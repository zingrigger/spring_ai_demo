package com.example.auth.token;

import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Customizes JWT claims.
 *
 * <p>Client-credentials tokens carry only the client identity and its granted
 * scopes. User tokens get the login account, the display name and the
 * organization binding that the login flow stored with the authorization —
 * never anything a client supplied.
 */
@Component
public class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        if (isClientCredentialsToken(context)) {
            return;
        }
        if (!(principalOf(context) instanceof Authentication authentication)) {
            return;
        }
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)
                || !(authentication.getDetails() instanceof OrganizationAuthorization organization)) {
            return;
        }
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            context.getClaims().claims((claims) -> {
                claims.put("preferred_username", user.account());
                claims.put("name", user.name());
                claims.put("org_id", String.valueOf(organization.orgId()));
                claims.put("org_name", organization.orgName());
                claims.put("roles", organization.roles());
            });
        }
        else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            // The ID token only needs the current organization on top of the
            // standard OIDC identity claims.
            context.getClaims().claim("org_id", String.valueOf(organization.orgId()));
        }
    }

    private static Object principalOf(JwtEncodingContext context) {
        OAuth2Authorization authorization = context.getAuthorization();
        return authorization == null ? null : authorization.getAttribute(Principal.class.getName());
    }

    private static boolean isClientCredentialsToken(JwtEncodingContext context) {
        return context.getPrincipal() instanceof OAuth2ClientAuthenticationToken;
    }
}
