package com.example.auth.web;

import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import com.example.auth.security.PlatformAdmin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPA 的 JSON 登录接口。登录态仍然是 auth-server 的 session：登录成功后把
 * SecurityContext 写入 session（并轮换 session id 防会话固定），SPA 不接触任何令牌。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthenticationManager authenticationManager;

    private final SecurityContextRepository securityContextRepository;

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    private final RequestCache requestCache;

    public AuthApiController(AuthenticationManager authenticationManager,
                             SecurityContextRepository securityContextRepository,
                             SessionAuthenticationStrategy sessionAuthenticationStrategy,
                             RequestCache requestCache) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.requestCache = requestCache;
    }

    @GetMapping("/session")
    public SessionResponse session(Authentication authentication, HttpServletRequest request,
                                   HttpServletResponse response, CsrfToken csrfToken) {
        // 读取 token 会触发 Spring Security 生成并下发 cookie，SPA 随后用它做写请求的 CSRF 头。
        csrfToken.getToken();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return new SessionResponse(false, null, null, null, false);
        }
        OrganizationAuthorization binding = authentication.getDetails() instanceof OrganizationAuthorization bound
                ? bound : null;
        SavedRequest savedRequest = this.requestCache.getRequest(request, response);
        boolean platformAdmin = authentication.getAuthorities().stream()
                .anyMatch((authority) -> PlatformAdmin.AUTHORITY.equals(authority.getAuthority()));
        return new SessionResponse(true,
                new UserView(user.account(), user.name()),
                binding == null ? null : new OrganizationView(binding.orgId(), binding.orgName()),
                savedRequest == null ? null : savedRequest.getRedirectUrl(),
                platformAdmin);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request,
                                   HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = this.authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.account(), body.password()));
        }
        catch (AuthenticationException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiError(ApiError.INVALID_CREDENTIALS));
        }
        this.sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
        // 组织绑定总是紧跟登录，saved request 留在 session 里由组织接口继续消费。
        return ResponseEntity.ok(new LoginResponse("/organizations"));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    public record LoginRequest(String account, String password) {
    }

    public record LoginResponse(String next) {
    }

    public record SessionResponse(boolean authenticated, UserView user, OrganizationView organization, String pending,
                                  boolean platformAdmin) {
    }
}
