package com.example.auth.web;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.Organization;
import com.example.auth.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 组织选择 API：只接受该用户真实关联的组织；单组织时直接绑定，不显示选择页。
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationApiController {

    private final IdentityRepository identityRepository;

    private final OrganizationBindingService bindingService;

    public OrganizationApiController(IdentityRepository identityRepository,
                                     OrganizationBindingService bindingService) {
        this.identityRepository = identityRepository;
        this.bindingService = bindingService;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication, HttpServletRequest request,
                                  HttpServletResponse response) {
        AuthenticatedUser user = currentUser(authentication);
        List<Organization> organizations = this.identityRepository.findOrganizations(user.id());
        if (organizations.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ApiError.ACCESS_DENIED));
        }
        if (organizations.size() == 1) {
            String next = this.bindingService.bind(user, organizations.get(0), authentication, request, response);
            return ResponseEntity.ok(new OrganizationsResponse(views(organizations), next));
        }
        return ResponseEntity.ok(new OrganizationsResponse(views(organizations), null));
    }

    @PostMapping
    public ResponseEntity<?> select(@RequestBody SelectOrganizationRequest body, Authentication authentication,
                                    HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser user = currentUser(authentication);
        Organization organization = this.identityRepository.findOrganizations(user.id()).stream()
                .filter((candidate) -> candidate.id() == body.orgId())
                .findFirst()
                .orElse(null);
        if (organization == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ApiError.ACCESS_DENIED));
        }
        String next = this.bindingService.bind(user, organization, authentication, request, response);
        return ResponseEntity.ok(new NextStepResponse(next));
    }

    private static AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private static List<OrganizationView> views(List<Organization> organizations) {
        return organizations.stream()
                .map((organization) -> new OrganizationView(organization.id(), organization.name()))
                .toList();
    }

    public record OrganizationsResponse(List<OrganizationView> organizations, String next) {
    }

    public record NextStepResponse(String next) {
    }

    public record SelectOrganizationRequest(long orgId) {
    }
}
