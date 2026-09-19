package com.example.auth.web;

import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 授权确认页的数据接口：客户端名称、请求的 scopes、服务端绑定的组织与当前用户。
 * Spring Authorization Server 会把请求参数重定向到 /consent 页，由 SPA 调用本接口取值。
 */
@RestController
@RequestMapping("/api/consent")
public class ConsentApiController {

    private final RegisteredClientRepository registeredClientRepository;

    public ConsentApiController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @GetMapping
    public ConsentResponse consent(@RequestParam("client_id") String clientId,
                                   @RequestParam(name = "scope", required = false) List<String> requestedScopes,
                                   @RequestParam(name = "state", required = false) String state,
                                   Authentication authentication) {
        RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);
        OrganizationAuthorization binding = authentication != null
                && authentication.getDetails() instanceof OrganizationAuthorization bound ? bound : null;
        AuthenticatedUser user = authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal ? principal : null;
        // Spring Authorization Server 用空格分隔传入 scopes；openid 是隐式 scope，不需要用户确认。
        List<String> scopes = requestedScopes == null ? List.of() : requestedScopes.stream()
                .flatMap((value) -> Arrays.stream(value.trim().split("\\s+")))
                .filter(StringUtils::hasText)
                .filter((scope) -> !OidcScopes.OPENID.equals(scope))
                .distinct()
                .toList();

        return new ConsentResponse(
                clientId,
                registeredClient != null ? registeredClient.getClientName() : clientId,
                scopes,
                state,
                binding == null ? null : new OrganizationView(binding.orgId(), binding.orgName()),
                user == null ? null : new UserView(user.account(), user.name()));
    }

    public record ConsentResponse(String clientId, String clientName, List<String> scopes, String state,
                                  OrganizationView organization, UserView user) {
    }
}
