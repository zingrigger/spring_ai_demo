package com.example.auth.web;

import com.example.auth.identity.IdentityRepository;
import com.example.auth.identity.Organization;
import com.example.auth.identity.Role;
import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务端绑定用户选择的组织：角色只按该 org_id 查询，绑定写入 Authentication.details，
 * 随 OAuth 授权记录一起持久化，客户端无法伪造组织或角色。
 */
@Component
public class OrganizationBindingService {

    private final IdentityRepository identityRepository;

    private final SecurityContextRepository securityContextRepository;

    private final RequestCache requestCache;

    public OrganizationBindingService(IdentityRepository identityRepository,
                                      SecurityContextRepository securityContextRepository,
                                      RequestCache requestCache) {
        this.identityRepository = identityRepository;
        this.securityContextRepository = securityContextRepository;
        this.requestCache = requestCache;
    }

    /**
     * 绑定组织并返回下一步应导航到的地址：优先恢复被缓存的授权请求，否则回首页。
     */
    public String bind(AuthenticatedUser user, Organization organization, Authentication authentication,
                       HttpServletRequest request, HttpServletResponse response) {
        // 可变 List 才能被框架的 Jackson 多态白名单接受（不可变 JDK 集合会被拒绝）。
        List<String> roles = this.identityRepository.findRoles(user.id(), organization.id()).stream()
                .map(Role::name)
                .collect(Collectors.toCollection(ArrayList::new));
        OrganizationAuthorization binding = new OrganizationAuthorization(
                user.id(), organization.id(), organization.name(), roles);

        UsernamePasswordAuthenticationToken updated = UsernamePasswordAuthenticationToken.authenticated(
                user, null, authentication.getAuthorities());
        updated.setDetails(binding);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(updated);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
        return continueUrl(request, response);
    }

    public String continueUrl(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest savedRequest = this.requestCache.getRequest(request, response);
        return savedRequest != null ? savedRequest.getRedirectUrl() : "/";
    }
}
