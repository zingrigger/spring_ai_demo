package com.example.auth.clientadmin;

import com.example.auth.security.AuthenticatedUser;
import com.example.auth.web.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 平台管理员专用的客户端管理 API；权限由 SecurityConfig 的 /api/admin/** 规则保证。
 */
@RestController
@RequestMapping("/api/admin/clients")
public class ClientAdminController {

    private final ClientManagementService clientManagementService;

    public ClientAdminController(ClientManagementService clientManagementService) {
        this.clientManagementService = clientManagementService;
    }

    @GetMapping
    public List<ClientSummaryView> list(@RequestParam(name = "query", required = false) String query) {
        return this.clientManagementService.list(query);
    }

    @GetMapping("/{clientId}")
    public ClientDetailView get(@PathVariable String clientId) {
        return this.clientManagementService.get(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateClientResponse create(@RequestBody CreateClientRequest request, Authentication authentication) {
        ClientManagementService.CreatedClient created =
                this.clientManagementService.create(request.toCommand(), actor(authentication));
        return new CreateClientResponse(created.client(), created.clientSecret());
    }

    @PutMapping("/{clientId}")
    public ClientDetailView update(@PathVariable String clientId, @RequestBody UpdateClientRequest request,
                                   Authentication authentication) {
        return this.clientManagementService.update(clientId, request.toCommand(), actor(authentication));
    }

    @PostMapping("/{clientId}/secret")
    public ClientSecretResponse rotateSecret(@PathVariable String clientId, Authentication authentication) {
        return new ClientSecretResponse(this.clientManagementService.rotateSecret(clientId, actor(authentication)));
    }

    @PostMapping("/{clientId}/enable")
    public ClientDetailView enable(@PathVariable String clientId, Authentication authentication) {
        return this.clientManagementService.setEnabled(clientId, true, actor(authentication));
    }

    @PostMapping("/{clientId}/disable")
    public ClientDetailView disable(@PathVariable String clientId, Authentication authentication) {
        return this.clientManagementService.setEnabled(clientId, false, actor(authentication));
    }

    @DeleteMapping("/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String clientId, Authentication authentication) {
        this.clientManagementService.delete(clientId, actor(authentication));
    }

    @ExceptionHandler(ClientManagementException.class)
    public ResponseEntity<ApiError> handle(ClientManagementException ex) {
        List<ApiError.Detail> details = ex.details().stream()
                .map((detail) -> new ApiError.Detail(detail.field(), detail.code()))
                .toList();
        return ResponseEntity.status(ex.status())
                .body(new ApiError(ex.error(), details.isEmpty() ? null : details));
    }

    private static AuthenticatedUser actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    public record CreateClientRequest(String clientId, String clientName, String type, List<String> redirectUris,
                                      List<String> postLogoutRedirectUris, List<String> scopes,
                                      Boolean requireAuthorizationConsent) {

        CreateClientCommand toCommand() {
            return new CreateClientCommand(this.clientId, this.clientName, this.type,
                    defaultList(this.redirectUris), defaultList(this.postLogoutRedirectUris),
                    defaultList(this.scopes), this.requireAuthorizationConsent);
        }

        private static List<String> defaultList(List<String> values) {
            return values == null ? List.of() : values;
        }
    }

    public record UpdateClientRequest(String clientName, List<String> redirectUris,
                                      List<String> postLogoutRedirectUris, List<String> scopes,
                                      List<String> grantTypes, List<String> clientAuthenticationMethods,
                                      Boolean requireAuthorizationConsent) {

        UpdateClientCommand toCommand() {
            return new UpdateClientCommand(this.clientName, this.redirectUris, this.postLogoutRedirectUris,
                    this.scopes, this.grantTypes, this.clientAuthenticationMethods, this.requireAuthorizationConsent);
        }
    }

    public record CreateClientResponse(ClientDetailView client, String clientSecret) {
    }

    public record ClientSecretResponse(String clientSecret) {
    }
}
