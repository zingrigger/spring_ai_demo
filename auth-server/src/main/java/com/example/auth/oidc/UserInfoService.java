package com.example.auth.oidc;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.Organization;
import com.example.auth.identity.Role;
import com.example.auth.identity.UserAccount;
import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the OIDC UserInfo response from the organization bound at login time.
 *
 * <p>The user, the organization and the roles are re-read from the read-only
 * identity tables on every call, so a revoked organization relationship is
 * reflected immediately; nothing comes from request parameters.
 */
@Service
public class UserInfoService {

    private final IdentityRepository identityRepository;

    public UserInfoService(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    public OidcUserInfo load(OidcUserInfoAuthenticationContext context) {
        Authentication authentication = userAuthentication(context.getAuthorization());
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        OrganizationAuthorization binding = (OrganizationAuthorization) authentication.getDetails();

        UserAccount account = this.identityRepository.findByAccount(user.account())
                .orElseThrow(UserInfoService::invalidToken);
        Organization organization = this.identityRepository.findOrganizations(account.id()).stream()
                .filter(candidate -> candidate.id() == binding.orgId())
                .findFirst()
                .orElseThrow(UserInfoService::invalidToken);
        List<String> roles = this.identityRepository.findRoles(account.id(), organization.id()).stream()
                .map(Role::name)
                .toList();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", authentication.getName());
        claims.put("preferred_username", account.account());
        claims.put("name", account.name());
        claims.put("org_id", String.valueOf(organization.id()));
        claims.put("org_name", organization.name());
        claims.put("roles", roles);
        return new OidcUserInfo(claims);
    }

    private static Authentication userAuthentication(OAuth2Authorization authorization) {
        Object attribute = authorization.getAttribute(Principal.class.getName());
        if (attribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser
                && authentication.getDetails() instanceof OrganizationAuthorization) {
            return authentication;
        }
        throw invalidToken();
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN));
    }
}
