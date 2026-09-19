package com.example.auth.web;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.Organization;
import com.example.auth.identity.Role;
import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Binds the organization a user authorizes from. Only organizations the user is
 * actually linked to are accepted; roles are read for that organization only.
 */
@Controller
public class OrganizationController {

    private final IdentityRepository identityRepository;
    private final SecurityContextRepository securityContextRepository;
    private final RequestCache requestCache;

    public OrganizationController(IdentityRepository identityRepository,
                                  SecurityContextRepository securityContextRepository,
                                  RequestCache requestCache) {
        this.identityRepository = identityRepository;
        this.securityContextRepository = securityContextRepository;
        this.requestCache = requestCache;
    }

    @GetMapping("/organizations")
    public String organizations(Authentication authentication, HttpServletRequest request,
                                HttpServletResponse response, Model model) {
        AuthenticatedUser user = currentUser(authentication);
        List<Organization> organizations = this.identityRepository.findOrganizations(user.id());
        if (organizations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
        if (organizations.size() == 1) {
            bind(user, organizations.get(0), authentication, request, response);
            return "redirect:" + continueUrl(request, response);
        }
        model.addAttribute("user", user);
        model.addAttribute("organizations", organizations);
        return "organizations";
    }

    @PostMapping("/organizations")
    public String selectOrganization(@RequestParam("orgId") long orgId, Authentication authentication,
                                     HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser user = currentUser(authentication);
        Organization organization = this.identityRepository.findOrganizations(user.id()).stream()
                .filter(candidate -> candidate.id() == orgId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied"));
        bind(user, organization, authentication, request, response);
        return "redirect:" + continueUrl(request, response);
    }

    private void bind(AuthenticatedUser user, Organization organization, Authentication authentication,
                      HttpServletRequest request, HttpServletResponse response) {
        List<String> roles = this.identityRepository.findRoles(user.id(), organization.id()).stream()
                .map(Role::name)
                .toList();
        OrganizationAuthorization binding = new OrganizationAuthorization(
                user.id(), organization.id(), organization.name(), roles);

        UsernamePasswordAuthenticationToken updated = UsernamePasswordAuthenticationToken.authenticated(
                user, null, authentication.getAuthorities());
        updated.setDetails(binding);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(updated);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
    }

    private String continueUrl(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest savedRequest = this.requestCache.getRequest(request, response);
        return savedRequest != null ? savedRequest.getRedirectUrl() : "/";
    }

    private static AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }
}
