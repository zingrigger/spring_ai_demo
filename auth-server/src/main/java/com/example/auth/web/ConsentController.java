package com.example.auth.web;

import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;

/**
 * Authorization confirmation page. It shows the client name, the requested
 * scopes and the organization the user bound on the server side, and submits
 * the consent back to the authorization endpoint.
 */
@Controller
public class ConsentController {

    private final RegisteredClientRepository registeredClientRepository;

    public ConsentController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @GetMapping("/oauth2/consent")
    public String consent(@RequestParam("client_id") String clientId,
                          @RequestParam(name = "scope", required = false) List<String> requestedScopes,
                          @RequestParam(name = "state", required = false) String state,
                          Authentication authentication, Model model) {
        RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);
        OrganizationAuthorization organization = authentication != null
                && authentication.getDetails() instanceof OrganizationAuthorization binding ? binding : null;
        // Spring Authorization Server passes the requested scopes space-separated;
        // "openid" is implicit and never needs explicit consent.
        List<String> scopes = requestedScopes == null ? List.of() : requestedScopes.stream()
                .flatMap((value) -> Arrays.stream(value.trim().split("\\s+")))
                .filter(StringUtils::hasText)
                .filter((scope) -> !OidcScopes.OPENID.equals(scope))
                .distinct()
                .toList();

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", registeredClient != null ? registeredClient.getClientName() : clientId);
        model.addAttribute("scopes", scopes);
        model.addAttribute("state", state);
        model.addAttribute("organization", organization);
        model.addAttribute("user", authentication != null ? authentication.getPrincipal() : null);
        return "consent";
    }
}
