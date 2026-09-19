package com.example.auth.token;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.Organization;
import com.example.auth.identity.Role;
import com.example.auth.identity.UserAccount;
import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-validates the organization relationship whenever a user token is refreshed.
 *
 * <p>Refresh tokens live for thirty days, so a user can lose access to the
 * organization in the meantime. The refreshed token then carries the current
 * organization name and roles, and the refresh fails with {@code invalid_grant}
 * once the relationship is gone.
 */
@Component
public class AuthorizationTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final IdentityRepository identityRepository;

    public AuthorizationTokenCustomizer(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                || !AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())) {
            return;
        }
        Authentication authentication = userAuthentication(context.getAuthorization());
        if (authentication == null) {
            return;
        }
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        OrganizationAuthorization binding = (OrganizationAuthorization) authentication.getDetails();

        UserAccount account = this.identityRepository.findByAccount(user.account())
                .orElseThrow(AuthorizationTokenCustomizer::invalidGrant);
        Organization organization = this.identityRepository.findOrganizations(account.id()).stream()
                .filter(candidate -> candidate.id() == binding.orgId())
                .findFirst()
                .orElseThrow(AuthorizationTokenCustomizer::invalidGrant);
        List<String> roles = new ArrayList<>(this.identityRepository.findRoles(account.id(), organization.id())
                .stream()
                .map(Role::name)
                .toList());

        context.getClaims().claims((claims) -> {
            claims.put("org_name", organization.name());
            claims.put("roles", roles);
        });
    }

    private static Authentication userAuthentication(OAuth2Authorization authorization) {
        if (authorization == null) {
            return null;
        }
        Object attribute = authorization.getAttribute(Principal.class.getName());
        if (attribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser
                && authentication.getDetails() instanceof OrganizationAuthorization) {
            return authentication;
        }
        return null;
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }
}
