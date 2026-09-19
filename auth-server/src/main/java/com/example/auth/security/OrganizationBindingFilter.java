package com.example.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * A signed-in user can only reach the authorization endpoint with an
 * organization bound on the server side. Without one, the pending request is
 * cached and the user is sent to the organization selection page, which
 * resumes the authorization request afterwards.
 */
public class OrganizationBindingFilter extends OncePerRequestFilter {

    private final AuthorizationServerSettings authorizationServerSettings;
    private final RequestCache requestCache;

    public OrganizationBindingFilter(AuthorizationServerSettings authorizationServerSettings,
                                     RequestCache requestCache) {
        this.authorizationServerSettings = authorizationServerSettings;
        this.requestCache = requestCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isAuthorizationEndpoint(request)
                && isUserWithoutOrganization(SecurityContextHolder.getContext().getAuthentication())) {
            this.requestCache.saveRequest(request, response);
            response.sendRedirect("/organizations");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthorizationEndpoint(HttpServletRequest request) {
        String endpoint = this.authorizationServerSettings.getAuthorizationEndpoint();
        String path = endpoint.startsWith("http") ? URI.create(endpoint).getPath() : endpoint;
        return path.equals(request.getRequestURI());
    }

    private static boolean isUserWithoutOrganization(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser
                && !(authentication.getDetails() instanceof OrganizationAuthorization);
    }
}
