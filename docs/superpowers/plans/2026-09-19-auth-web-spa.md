# Auth Web 前后端分离（Vue 3 SPA）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 auth-server 的登录、组织选择、授权确认页面从 Thymeleaf 服务端渲染改成独立 `auth-web/`（Vue 3 + TS + Tailwind + vue-i18n）SPA，由 JSON API 驱动，构建产物默认打进 auth-server jar。

**Architecture:** 浏览器只面对 auth-server 一个 origin；SPA 通过 `/api/**` JSON 接口驱动登录/组织/consent 数据，consent 的「同意」仍是浏览器顶层表单 POST 到 `/oauth2/authorize`。Thymeleaf 与相关 controller/模板全部移除；前端由 `frontend-maven-plugin` 构建并复制到 `target/classes/static`。

**Tech Stack:** Spring Boot 4.0.8、Spring Security 7.0.7（含 `spring-security-oauth2-authorization-server` 7.0.7）、JUnit 5 + MockMvc + MySQL 8 Testcontainers、Vue 3 + TypeScript + Vite + Vue Router + Tailwind CSS v4 + vue-i18n + Vitest、frontend-maven-plugin。

**Spec:** `docs/superpowers/specs/2026-09-19-auth-web-spa-design.md`

## Global Constraints

- 部署同源：不引入任何 CORS 配置；SPA 与 `/api`、`/oauth2` 同域。
- OAuth/OIDC 协议行为、令牌格式、数据库 schema、`weather-mcp-server` 均不变。
- Spring Authorization Server 默认忽略其全部端点的 CSRF（`csrf.ignoringRequestMatchers(endpointsMatcher)`），consent 表单不含 `_csrf`。
- 应用链 `/api/**` 写请求必须校验 CSRF：`CookieCsrfTokenRepository.withHttpOnlyFalse()` + 明文 `CsrfTokenRequestAttributeHandler`（不要用 XOR 处理器，否则 SPA 发送 cookie 原始值会失败）。
- 登录失败统一 `401 {"error":"invalid_credentials"}`；越权组织 `403 {"error":"access_denied"}`。
- 组织绑定必须来自 `sys_user_org_role` 联查结果；角色只按所选 `org_id` 查询；`sys_*` 表只读。
- 登录成功后必须轮换 session id（`ChangeSessionIdAuthenticationStrategy`），不得依赖已移除的 `formLogin`。
- 页面文案中英双语（vue-i18n），默认按 `navigator.language` 选择、`zh` 兜底，选择写入 `localStorage` 键 `auth-web.locale`。
- 每个任务结束后提交一次小粒度 commit；提交信息使用任务中给出的原文。

---

### Task 1: JSON 认证 API（session / login / logout）

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/web/ApiError.java`
- Create: `auth-server/src/main/java/com/example/auth/web/UserView.java`
- Create: `auth-server/src/main/java/com/example/auth/web/OrganizationView.java`
- Create: `auth-server/src/main/java/com/example/auth/web/AuthApiController.java`
- Modify: `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java`
- Test: `auth-server/src/test/java/com/example/auth/web/AuthApiControllerTest.java`

**Interfaces:**
- Consumes: `com.example.auth.security.AuthenticatedUser`、`com.example.auth.security.OrganizationAuthorization`、现有 `SecurityContextRepository`/`RequestCache` bean。
- Produces（后续任务与前端依赖的 JSON 契约）：
  - `GET /api/auth/session` → `200 {"authenticated":bool,"user":{"account","name"}|null,"organization":{"id","name"}|null,"pending":string|null}`
  - `POST /api/auth/login` body `{"account","password"}` → `200 {"next":"/organizations"}` 或 `401 {"error":"invalid_credentials"}`
  - `POST /api/auth/logout` → `204`
  - `ApiError(String error)`、`UserView(String account, String name)`、`OrganizationView(long id, String name)` 三个公共 record。

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/web/AuthApiControllerTest.java`：

```java
package com.example.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON sign-in API used by the SPA: session probe, login and logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class AuthApiControllerTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousSessionHasNoUser() throws Exception {
        this.mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void loginStoresTheSessionAndReturnsTheNextStep() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.account").value("alice"))
                .andExpect(jsonPath("$.organization").doesNotExist());
    }

    @Test
    void sessionExposesThePendingAuthorizationRequest() throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(containsString("/oauth2/authorize")));
    }

    @Test
    void unknownAccountsAndWrongPasswordsFailIdentically() throws Exception {
        this.mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));

        this.mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void loginWithoutACsrfTokenIsRejected() throws Exception {
        this.mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }

    private void login(MockHttpSession session, String account, String password) throws Exception {
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("/organizations"));
    }

    private static MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", "auth-web-public")
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/auth-server")
                .queryParam("state", "test-state")
                .queryParam("code_challenge", "0123456789012345678901234567890123456789012")
                .queryParam("code_challenge_method", "S256");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server test -Dtest=AuthApiControllerTest -DfailIfNoTests=false`
Expected: FAIL —— `/api/auth/session` 与 `/api/auth/login` 返回 401/404（端点还不存在）

- [ ] **Step 3: 实现 API 与安全配置**

创建 `auth-server/src/main/java/com/example/auth/web/ApiError.java`：

```java
package com.example.auth.web;

/**
 * 稳定的 API 错误体：{"error":"..."}。前端按 error 码做文案映射。
 */
public record ApiError(String error) {

    public static final String INVALID_CREDENTIALS = "invalid_credentials";

    public static final String ACCESS_DENIED = "access_denied";
}
```

创建 `auth-server/src/main/java/com/example/auth/web/UserView.java`：

```java
package com.example.auth.web;

/** 前端展示用的当前用户。 */
public record UserView(String account, String name) {
}
```

创建 `auth-server/src/main/java/com/example/auth/web/OrganizationView.java`：

```java
package com.example.auth.web;

/** 前端展示用的组织。 */
public record OrganizationView(long id, String name) {
}
```

创建 `auth-server/src/main/java/com/example/auth/web/AuthApiController.java`：

```java
package com.example.auth.web;

import com.example.auth.security.AuthenticatedUser;
import com.example.auth.security.OrganizationAuthorization;
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
            return new SessionResponse(false, null, null, null);
        }
        OrganizationAuthorization binding = authentication.getDetails() instanceof OrganizationAuthorization bound
                ? bound : null;
        SavedRequest savedRequest = this.requestCache.getRequest(request, response);
        return new SessionResponse(true,
                new UserView(user.account(), user.name()),
                binding == null ? null : new OrganizationView(binding.orgId(), binding.orgName()),
                savedRequest == null ? null : savedRequest.getRedirectUrl());
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

    public record SessionResponse(boolean authenticated, UserView user, OrganizationView organization, String pending) {
    }
}
```

修改 `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java`：把放行列表扩展到两个新端点，并新增两个 bean（其余保持不动）：

```java
        http.authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/login", "/error", "/actuator/health",
                        "/api/auth/session", "/api/auth/login").permitAll()
                .anyRequest().authenticated());
```

```java
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        // 与框架共用同一个 AuthenticationManager：它已包含 @Component IdentityAuthenticationProvider。
        return configuration.getAuthenticationManager();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }
```

新增 import：

```java
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server test -Dtest=AuthApiControllerTest -DfailIfNoTests=false`
Expected: PASS（6 个测试）

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/web auth-server/src/main/java/com/example/auth/config/SecurityConfig.java auth-server/src/test/java/com/example/auth/web/AuthApiControllerTest.java
git commit -m "feat: add SPA sign-in JSON API"
```

---

### Task 2: 组织 JSON API 与绑定服务

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/web/OrganizationBindingService.java`
- Create: `auth-server/src/main/java/com/example/auth/web/OrganizationApiController.java`
- Test: `auth-server/src/test/java/com/example/auth/web/OrganizationApiControllerTest.java`

**Interfaces:**
- Consumes: `IdentityRepository.findOrganizations/findRoles`、`OrganizationBindingService.bind(...)`（Task 1 的 `OrganizationView`/`ApiError`）。
- Produces:
  - `GET /api/organizations` → `200 {"organizations":[{"id","name"}],"next":string|null}`（单组织时服务端已绑定并返回 `next`）；无组织 `403 {"error":"access_denied"}`
  - `POST /api/organizations` body `{"orgId":number}` → `200 {"next":string}`；不属于该用户 `403 {"error":"access_denied"}`
  - `OrganizationBindingService.bind(user, organization, authentication, request, response) -> String next`

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/web/OrganizationApiControllerTest.java`：

```java
package com.example.auth.web;

import com.example.auth.security.OrganizationAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织选择 API：列表、单组织自动绑定、越权拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class OrganizationApiControllerTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void multiOrganizationUserGetsTheListAndNoNextStep() throws Exception {
        MockHttpSession session = signedIn("alice", "alice-password");

        this.mockMvc.perform(get("/api/organizations").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(2))
                .andExpect(jsonPath("$.next").doesNotExist());
    }

    @Test
    void selectingAnOrganizationBindsItAndResumesThePendingRequest() throws Exception {
        MockHttpSession session = signedIn("alice", "alice-password");

        this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value(containsString("/oauth2/authorize")));

        OrganizationAuthorization binding = binding(session);
        assertThat(binding).isNotNull();
        assertThat(binding.orgId()).isEqualTo(20L);
        assertThat(binding.roles()).containsExactly("AUDITOR");
    }

    @Test
    void singleOrganizationUserIsBoundAutomatically() throws Exception {
        MockHttpSession session = signedIn("bob", "bob-password");

        this.mockMvc.perform(get("/api/organizations").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(1))
                .andExpect(jsonPath("$.next").value(containsString("/oauth2/authorize")));

        OrganizationAuthorization binding = binding(session);
        assertThat(binding).isNotNull();
        assertThat(binding.orgId()).isEqualTo(20L);
    }

    @Test
    void organizationsOfAnotherUserAreRejected() throws Exception {
        MockHttpSession session = signedIn("alice", "alice-password");

        this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":30}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    private MockHttpSession signedIn(String account, String password) throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private OrganizationAuthorization binding(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null || context.getAuthentication() == null) {
            return null;
        }
        Object details = context.getAuthentication().getDetails();
        return details instanceof OrganizationAuthorization authorization ? authorization : null;
    }

    private static MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", "auth-web-public")
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/auth-server")
                .queryParam("state", "test-state")
                .queryParam("code_challenge", "0123456789012345678901234567890123456789012")
                .queryParam("code_challenge_method", "S256");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server test -Dtest=OrganizationApiControllerTest -DfailIfNoTests=false`
Expected: FAIL —— `/api/organizations` 404（端点不存在）

- [ ] **Step 3: 实现绑定服务与控制器**

创建 `auth-server/src/main/java/com/example/auth/web/OrganizationBindingService.java`（把 `OrganizationController` 中的绑定逻辑原样迁出，行为不变）：

```java
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
```

创建 `auth-server/src/main/java/com/example/auth/web/OrganizationApiController.java`：

```java
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
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server test -Dtest='AuthApiControllerTest,OrganizationApiControllerTest' -DfailIfNoTests=false`
Expected: PASS（两个测试类全部通过）

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/web auth-server/src/test/java/com/example/auth/web/OrganizationApiControllerTest.java
git commit -m "feat: add organization JSON API"
```

---

### Task 3: 授权确认（consent）JSON API

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/web/ConsentApiController.java`
- Test: `auth-server/src/test/java/com/example/auth/web/ConsentApiControllerTest.java`

**Interfaces:**
- Consumes: `RegisteredClientRepository`、Task 1 的 `UserView`/`OrganizationView`。
- Produces: `GET /api/consent?client_id&scope&state` → `200 {"clientId","clientName","scopes":[string],"state","organization","user"}`；`scope` 参数可重复，服务端拆分、去重并过滤 `openid`。

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/web/ConsentApiControllerTest.java`：

```java
package com.example.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * consent 数据接口：客户端名称、请求的 scopes、当前组织与用户。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ConsentApiControllerTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void consentPayloadSplitsFiltersAndDeduplicatesScopes() throws Exception {
        MockHttpSession session = boundSession("alice", "alice-password", 10L);

        MvcResult result = this.mockMvc.perform(get("/api/consent").session(session)
                        .param("client_id", "auth-web-public")
                        .param("scope", "openid profile")
                        .param("scope", "profile weather:read")
                        .param("state", "test-state"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode payload = this.objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("clientId").asText()).isEqualTo("auth-web-public");
        assertThat(payload.path("clientName").asText()).isEqualTo("Auth Web Client");
        assertThat(payload.path("state").asText()).isEqualTo("test-state");
        assertThat(payload.path("organization").path("name").asText()).isEqualTo("Alpha");
        assertThat(payload.path("user").path("account").asText()).isEqualTo("alice");
        List<String> scopes = new ArrayList<>();
        payload.path("scopes").forEach((scope) -> scopes.add(scope.asText()));
        assertThat(scopes).containsExactly("profile", "weather:read");
    }

    @Test
    void unknownClientFallsBackToTheClientId() throws Exception {
        MockHttpSession session = boundSession("alice", "alice-password", 10L);

        MvcResult result = this.mockMvc.perform(get("/api/consent").session(session)
                        .param("client_id", "not-registered")
                        .param("scope", "profile"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode payload = this.objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("clientName").asText()).isEqualTo("not-registered");
    }

    private MockHttpSession boundSession(String account, String password, long orgId) throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        return session;
    }

    private static MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", "auth-web-public")
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/auth-server")
                .queryParam("state", "test-state")
                .queryParam("code_challenge", "0123456789012345678901234567890123456789012")
                .queryParam("code_challenge_method", "S256");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server test -Dtest=ConsentApiControllerTest -DfailIfNoTests=false`
Expected: FAIL —— `/api/consent` 404

- [ ] **Step 3: 实现 consent API**

创建 `auth-server/src/main/java/com/example/auth/web/ConsentApiController.java`（逻辑与现有 `ConsentController` 一致，只把 Model 换成 JSON）：

```java
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server test -Dtest=ConsentApiControllerTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/web/ConsentApiController.java auth-server/src/test/java/com/example/auth/web/ConsentApiControllerTest.java
git commit -m "feat: add consent JSON API"
```

---

### Task 4: consent 路由切到 /consent 并迁移授权码流程测试

**Files:**
- Modify: `auth-server/src/main/java/com/example/auth/config/AuthorizationServerConfig.java`
- Delete: `auth-server/src/main/java/com/example/auth/web/ConsentController.java`
- Delete: `auth-server/src/main/resources/templates/consent.html`
- Modify: `auth-server/src/test/java/com/example/auth/oauth/AuthorizationCodeFlow.java`
- Modify: `auth-server/src/test/java/com/example/auth/oauth/OAuthAuthorizationCodeFlowTest.java`

**Interfaces:**
- Consumes: Task 1-3 的 JSON API。
- Produces: `AuthorizationCodeFlow.consentPayload(account, password, orgId, scope) -> JsonNode`（替代原 `consentPage(String)`），供后续所有流程测试使用。

- [ ] **Step 1: 先改测试辅助类与断言（此时会失败）**

把 `AuthorizationCodeFlow` 中「登录 + 选择组织」改为 JSON API：

```java
    private MockHttpSession loginAndSelectOrganization(String account, String password, long orgId, String scope)
            throws Exception {
        MvcResult start = this.mockMvc.perform(authorizeRequest(scope, VERIFIER))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        MvcResult organizations = this.mockMvc.perform(get("/api/organizations").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode payload = this.objectMapper.readTree(organizations.getResponse().getContentAsString());
        if (payload.path("next").isNull() || payload.path("next").isMissingNode()) {
            this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orgId\":" + orgId + "}"))
                    .andExpect(status().isOk());
        }
        return session;
    }
```

把 consent 相关方法替换为 JSON 版本（删除 `consentPageHtml`、`hiddenField`、`scopesOf`、`inputValues` 及 `Matcher`/`Pattern` 相关 import）：

新增 `import java.util.Map;`（原文件用全限定名 `java.util.Map`，改用简单名后需要导入）。

```java
    /**
     * 返回 consent 数据接口的 JSON 载荷（含客户端名称、scopes、state）。
     */
    public JsonNode consentPayload(String account, String password, long orgId, String scope) throws Exception {
        MockHttpSession session = loginAndSelectOrganization(account, password, orgId, scope);
        MvcResult authorization = this.mockMvc.perform(authorizeRequest(scope, VERIFIER).session(session))
                .andReturn();
        return consentPayload(session, authorization);
    }

    private JsonNode consentPayload(MockHttpSession session, MvcResult authorization) throws Exception {
        String location = authorization.getResponse().getRedirectedUrl();
        assertThat(location).as("authorization endpoint did not redirect to the consent page").isNotNull();
        assertThat(java.net.URI.create(location).getPath()).endsWith("/consent");
        Map<String, List<String>> parameters = queryParameters(location);

        MockHttpServletRequestBuilder request = get("/api/consent").session(session)
                .param("client_id", parameters.get("client_id").getFirst());
        parameters.getOrDefault("scope", List.of()).forEach((scope) -> request.param("scope", scope));
        if (parameters.containsKey("state")) {
            request.param("state", parameters.get("state").getFirst());
        }
        MvcResult result = this.mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String submitConsent(MockHttpSession session, JsonNode consent) throws Exception {
        MockHttpServletRequestBuilder request = post("/oauth2/authorize").session(session)
                .param("client_id", consent.path("clientId").asText());
        if (consent.hasNonNull("state")) {
            request.param("state", consent.path("state").asText());
        }
        consent.path("scopes").forEach((scope) -> request.param("scope", scope.asText()));
        return this.mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }
```

同时把 `authorize(...)` 改为使用新方法：

```java
        else {
            location = submitConsent(session, consentPayload(session, authorization));
        }
```

把 `queryParameters` 改为支持重复参数（`scope` 会重复出现）：

```java
    private static Map<String, List<String>> queryParameters(String url) {
        Map<String, List<String>> decoded = new java.util.LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach(
                (name, values) -> decoded.put(name, values.stream()
                        .map((value) -> java.net.URLDecoder.decode(value, StandardCharsets.UTF_8))
                        .toList()));
        return decoded;
    }
```

`OAuthAuthorizationCodeFlowTest` 中的 consent 断言改为：

```java
    @Test
    void consentEndpointShowsTheClientTheScopesAndTheOrganization() throws Exception {
        JsonNode consent = this.flow.consentPayload("alice", "alice-password", 10L, "openid profile");

        assertThat(consent.path("clientName").asText()).isEqualTo("Auth Web Client");
        assertThat(consent.path("organization").path("name").asText()).isEqualTo("Alpha");
        assertThat(consent.path("user").path("account").asText()).isEqualTo("alice");
        List<String> scopes = new ArrayList<>();
        consent.path("scopes").forEach((scope) -> scopes.add(scope.asText()));
        assertThat(scopes).containsExactly("profile");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server test -Dtest=OAuthAuthorizationCodeFlowTest -DfailIfNoTests=false`
Expected: FAIL —— 断言 `/consent?` 失败（当前仍重定向到 `/oauth2/consent`）

- [ ] **Step 3: 切换 consent 路由并删除旧页面**

修改 `AuthorizationServerConfig`：

```java
            authorizationServer.authorizationEndpoint(
                    (authorizationEndpoint) -> authorizationEndpoint.consentPage("/consent"));
```

删除文件：`auth-server/src/main/java/com/example/auth/web/ConsentController.java`、
`auth-server/src/main/resources/templates/consent.html`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server test -Dtest=OAuthAuthorizationCodeFlowTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A auth-server/src/main/java/com/example/auth auth-server/src/main/resources/templates auth-server/src/test/java/com/example/auth/oauth
git commit -m "refactor: serve consent flow through the JSON API"
```

---

### Task 5: auth-web 脚手架、i18n 与语言切换

**Files:**
- Create: `auth-web/`（Vite `vue-ts` 模板脚手架）
- Create: `auth-web/vitest.config.ts`、`auth-web/src/i18n/{index,zh,en}.ts`、`auth-web/src/components/LocaleSwitch.vue`、`auth-web/src/assets/main.css`
- Modify: `auth-web/vite.config.ts`、`auth-web/index.html`、`auth-web/src/main.ts`、`auth-web/src/App.vue`、`auth-web/package.json`
- Delete: `auth-web/src/components/HelloWorld.vue`、`auth-web/src/style.css`、`auth-web/public/vite.svg`
- Test: `auth-web/tests/locale-switch.test.ts`

**Interfaces:**
- Produces: `i18n`（vue-i18n 实例）、`setLocale(locale: 'zh'|'en')`、`detectLocale(): 'zh'|'en'`、`LOCALE_KEY = 'auth-web.locale'`、`<LocaleSwitch />`。

- [ ] **Step 1: 生成脚手架并安装依赖**

```bash
cd /Users/rogerz/workspace/spring_ai_demo
npx --yes create-vite@latest auth-web --template vue-ts
cd auth-web
npm install
npm install vue-router@4 vue-i18n@11
npm install -D tailwindcss @tailwindcss/vite vitest jsdom @vue/test-utils
npm pkg set scripts.test="vitest run"
```

Expected: `auth-web/` 生成、`package-lock.json` 生成、`npm pkg set` 把 `test` 脚本写入 `package.json`。

- [ ] **Step 2: 清理模板痕迹并接线 Tailwind / Vitest**

```bash
cd /Users/rogerz/workspace/spring_ai_demo/auth-web
rm src/components/HelloWorld.vue src/style.css public/vite.svg
mkdir -p src/i18n src/assets tests
```

替换 `auth-web/vite.config.ts`：

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8083',
      '/oauth2': 'http://localhost:8083',
      '/.well-known': 'http://localhost:8083',
      '/userinfo': 'http://localhost:8083',
      '/actuator': 'http://localhost:8083',
    },
  },
})
```

创建 `auth-web/vitest.config.ts`：

```ts
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
  },
})
```

替换 `auth-web/index.html`（`lang` 与标题，移除 Vite favicon）：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Auth Server</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

创建 `auth-web/src/assets/main.css`：

```css
@import "tailwindcss";

body {
  @apply bg-slate-50 text-slate-900 antialiased;
}
```

- [ ] **Step 3: 写失败的测试**

创建 `auth-web/tests/locale-switch.test.ts`：

```ts
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import LocaleSwitch from '../src/components/LocaleSwitch.vue'
import { LOCALE_KEY, i18n, setLocale } from '../src/i18n'

describe('LocaleSwitch', () => {
  beforeEach(() => {
    localStorage.clear()
    setLocale('zh')
  })

  it('switches the active locale and persists the choice', async () => {
    const wrapper = mount(LocaleSwitch, { global: { plugins: [i18n] } })

    await wrapper.get('button[data-locale="en"]').trigger('click')

    expect(i18n.global.locale.value).toBe('en')
    expect(localStorage.getItem(LOCALE_KEY)).toBe('en')
  })
})
```

- [ ] **Step 4: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/components/LocaleSwitch.vue` 与 `../src/i18n` 不存在

- [ ] **Step 5: 实现 i18n 与语言切换组件**

创建 `auth-web/src/i18n/zh.ts`：

```ts
export default {
  brand: { name: 'Auth Server', slogan: '安全登录，\n从这里开始。' },
  login: {
    title: '登录',
    subtitle: '使用你的组织账号继续',
    account: '账号',
    password: '密码',
    submit: '登录',
    submitting: '登录中…',
    invalid: '账号或密码不正确。',
    failed: '登录失败，请稍后重试。',
  },
  organizations: {
    title: '选择组织',
    subtitle: '你属于多个组织，选择一个继续',
    submit: '继续',
    denied: '当前账号没有可用的组织，请联系管理员。',
    failed: '加载组织失败，请刷新重试。',
  },
  consent: {
    title: '授权确认',
    subtitle: '{client} 请求访问你的账号',
    asOrganization: '以 {organization} 身份授权',
    approve: '同意授权',
    failed: '加载授权信息失败，请回到客户端重新发起。',
  },
  home: {
    title: '已登录',
    subtitle: '当前没有待处理的授权请求',
    account: '账号',
    organization: '组织',
    logout: '退出登录',
  },
  scope: {
    profile: '读取基本资料',
    weatherRead: '读取天气数据',
  },
}
```

创建 `auth-web/src/i18n/en.ts`：

```ts
export default {
  brand: { name: 'Auth Server', slogan: 'Secure sign-in,\nstarts here.' },
  login: {
    title: 'Sign in',
    subtitle: 'Continue with your organization account',
    account: 'Account',
    password: 'Password',
    submit: 'Sign in',
    submitting: 'Signing in…',
    invalid: 'Invalid account or password.',
    failed: 'Sign-in failed. Please try again.',
  },
  organizations: {
    title: 'Choose an organization',
    subtitle: 'You belong to several organizations. Pick one to continue.',
    submit: 'Continue',
    denied: 'This account has no organization. Please contact an administrator.',
    failed: 'Could not load organizations. Please refresh.',
  },
  consent: {
    title: 'Authorize access',
    subtitle: '{client} requests access to your account',
    asOrganization: 'Authorizing as {organization}',
    approve: 'Approve',
    failed: 'Could not load the authorization request. Please restart the flow from your client.',
  },
  home: {
    title: 'Signed in',
    subtitle: 'There is no pending authorization request',
    account: 'Account',
    organization: 'Organization',
    logout: 'Sign out',
  },
  scope: {
    profile: 'Read your basic profile',
    weatherRead: 'Read weather data',
  },
}
```

创建 `auth-web/src/i18n/index.ts`：

```ts
import { createI18n } from 'vue-i18n'
import en from './en'
import zh from './zh'

export const LOCALE_KEY = 'auth-web.locale'

export type Locale = 'zh' | 'en'

export function detectLocale(): Locale {
  try {
    const saved = localStorage.getItem(LOCALE_KEY)
    if (saved === 'zh' || saved === 'en') return saved
  } catch {
    // 隐私模式下 localStorage 可能不可用，退回浏览器语言
  }
  const language = navigator.language?.toLowerCase() ?? ''
  return language.startsWith('en') ? 'en' : 'zh'
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: 'zh',
  messages: { zh, en },
})

export function setLocale(locale: Locale): void {
  i18n.global.locale.value = locale
  try {
    localStorage.setItem(LOCALE_KEY, locale)
  } catch {
    // 忽略存储失败：语言仍然在当前页面生效
  }
}
```

创建 `auth-web/src/components/LocaleSwitch.vue`：

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale, type Locale } from '../i18n'

const { locale } = useI18n()
const current = computed(() => locale.value as Locale)
const options: { value: Locale; label: string }[] = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'EN' },
]

function switchTo(next: Locale): void {
  if (next !== current.value) setLocale(next)
}
</script>

<template>
  <div class="inline-flex overflow-hidden rounded-full border border-slate-200 text-xs">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      :data-locale="option.value"
      class="px-3 py-1 transition"
      :class="option.value === current
        ? 'bg-indigo-600 text-white'
        : 'bg-white text-slate-500 hover:bg-slate-50'"
      @click="switchTo(option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>
```

替换 `auth-web/src/App.vue`（Task 12 会换成 `RouterView`）：

```vue
<script setup lang="ts">
import LocaleSwitch from './components/LocaleSwitch.vue'
</script>

<template>
  <main class="flex min-h-screen items-center justify-center">
    <LocaleSwitch />
  </main>
</template>
```

替换 `auth-web/src/main.ts`：

```ts
import { createApp } from 'vue'
import App from './App.vue'
import { i18n } from './i18n'
import './assets/main.css'

createApp(App).use(i18n).mount('#app')
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd auth-web && npm run test && npm run build`
Expected: PASS，且 `vue-tsc` + `vite build` 无错误

- [ ] **Step 7: 提交**

```bash
git add auth-web
git commit -m "feat: scaffold auth-web with tailwind and i18n"
```

---

### Task 6: API 客户端与会话接口封装

**Files:**
- Create: `auth-web/src/api/client.ts`、`auth-web/src/api/auth.ts`、`auth-web/src/navigation.ts`
- Test: `auth-web/tests/api-client.test.ts`

**Interfaces:**
- Produces:
  - `api<T>(path: string, init?: RequestInit): Promise<T>`、`ApiError(status, code)`、`readCookie(name): string|null`
  - `getSession(): Promise<SessionState>`、`login(account, password): Promise<{next: string}>`、`logout(): Promise<void>`
  - `listOrganizations(): Promise<{organizations: OrganizationView[]; next: string|null}>`
  - `selectOrganization(orgId: number): Promise<{next: string}>`
  - `getConsent(params: {clientId: string; scopes: string[]; state: string|null}): Promise<ConsentState>`
  - `navigate(url: string): void`

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/api-client.test.ts`：

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../src/api/client'

describe('api client', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token-123'
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('sends the CSRF cookie as a header on writes', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ next: '/organizations' }), { status: 200 }))

    const result = await api<{ next: string }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ account: 'alice', password: 'alice-password' }),
    })

    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(init?.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('token-123')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init?.credentials).toBe('same-origin')
    expect(result.next).toBe('/organizations')
  })

  it('maps error bodies to ApiError', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ error: 'invalid_credentials' }), { status: 401 }))

    await expect(api('/api/auth/login', { method: 'POST', body: '{}' }))
      .rejects.toMatchObject({ status: 401, code: 'invalid_credentials' })
    await expect(api('/api/auth/login', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(ApiError)
  })

  it('resolves undefined for empty 204 responses', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }))

    await expect(api('/api/auth/logout', { method: 'POST' })).resolves.toBeUndefined()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/api/client` 不存在

- [ ] **Step 3: 实现客户端**

创建 `auth-web/src/api/client.ts`：

```ts
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code)
    this.name = 'ApiError'
  }
}

export function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (init.body !== undefined) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie('XSRF-TOKEN')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }

  const response = await fetch(path, { ...init, method, headers, credentials: 'same-origin' })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : undefined
  if (!response.ok) {
    throw new ApiError(response.status, payload?.error ?? `http_${response.status}`)
  }
  return payload as T
}
```

创建 `auth-web/src/api/auth.ts`：

```ts
import { api } from './client'

export interface UserView {
  account: string
  name: string
}

export interface OrganizationView {
  id: number
  name: string
}

export interface SessionState {
  authenticated: boolean
  user: UserView | null
  organization: OrganizationView | null
  pending: string | null
}

export interface ConsentState {
  clientId: string
  clientName: string
  scopes: string[]
  state: string | null
  organization: OrganizationView | null
  user: UserView
}

export function getSession(): Promise<SessionState> {
  return api<SessionState>('/api/auth/session')
}

export function login(account: string, password: string): Promise<{ next: string }> {
  return api<{ next: string }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ account, password }),
  })
}

export function logout(): Promise<void> {
  return api<void>('/api/auth/logout', { method: 'POST' })
}

export function listOrganizations(): Promise<{ organizations: OrganizationView[]; next: string | null }> {
  return api<{ organizations: OrganizationView[]; next: string | null }>('/api/organizations')
}

export function selectOrganization(orgId: number): Promise<{ next: string }> {
  return api<{ next: string }>('/api/organizations', {
    method: 'POST',
    body: JSON.stringify({ orgId }),
  })
}

export function getConsent(params: { clientId: string; scopes: string[]; state: string | null }): Promise<ConsentState> {
  const query = new URLSearchParams()
  query.set('client_id', params.clientId)
  params.scopes.forEach((scope) => query.append('scope', scope))
  if (params.state) query.set('state', params.state)
  return api<ConsentState>(`/api/consent?${query.toString()}`)
}
```

创建 `auth-web/src/navigation.ts`：

```ts
/**
 * 顶层导航：next 可能是 SPA 路由（/organizations）也可能是协议地址
 * （/oauth2/authorize?…），统一交给浏览器处理，保证服务端能继续 302。
 */
export function navigate(url: string): void {
  window.location.assign(url)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd auth-web && npm run test`
Expected: PASS（api client 3 个用例 + Task 5 的 1 个用例）

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/api auth-web/src/navigation.ts auth-web/tests/api-client.test.ts
git commit -m "feat: add auth-web API client"
```

---

### Task 7: 品牌分栏布局组件

**Files:**
- Create: `auth-web/src/components/AuthLayout.vue`
- Test: `auth-web/tests/auth-layout.test.ts`

**Interfaces:**
- Consumes: `i18n`、`<LocaleSwitch />`。
- Produces: `<AuthLayout :title :subtitle>`，默认插槽渲染表单；品牌分栏在 `<md` 断点隐藏。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/auth-layout.test.ts`：

```ts
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import AuthLayout from '../src/components/AuthLayout.vue'
import { i18n, setLocale } from '../src/i18n'

describe('AuthLayout', () => {
  beforeEach(() => setLocale('zh'))

  it('renders the brand column, the locale switch and the slot', () => {
    const wrapper = mount(AuthLayout, {
      props: { title: '登录', subtitle: '使用你的组织账号继续' },
      slots: { default: '<input name="account" />' },
      global: { plugins: [i18n] },
    })

    expect(wrapper.text()).toContain('Auth Server')
    expect(wrapper.text()).toContain('安全登录')
    expect(wrapper.get('input[name="account"]').exists()).toBe(true)
    expect(wrapper.find('button[data-locale="en"]').exists()).toBe(true)
    expect(wrapper.get('aside').classes()).toContain('hidden')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/components/AuthLayout.vue` 不存在

- [ ] **Step 3: 实现布局组件**

创建 `auth-web/src/components/AuthLayout.vue`：

```vue
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import LocaleSwitch from './LocaleSwitch.vue'

defineProps<{ title: string; subtitle?: string }>()

const { t } = useI18n()
</script>

<template>
  <div class="min-h-screen md:flex">
    <aside class="hidden bg-gradient-to-br from-indigo-600 via-violet-600 to-purple-500 p-10 text-white md:flex md:w-2/5 md:flex-col md:justify-between">
      <div class="flex items-center gap-2 text-sm font-semibold">
        <span class="h-6 w-6 rounded-md bg-white/90"></span>
        <span>{{ t('brand.name') }}</span>
      </div>
      <div>
        <p class="whitespace-pre-line text-2xl font-semibold leading-snug">{{ t('brand.slogan') }}</p>
        <p class="mt-3 text-sm text-white/75">OAuth 2.1 / OIDC</p>
      </div>
    </aside>
    <main class="flex min-h-screen flex-1 items-center justify-center bg-slate-50 p-6">
      <div class="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div class="mb-4 flex justify-end">
          <LocaleSwitch />
        </div>
        <h1 class="text-lg font-semibold text-slate-900">{{ title }}</h1>
        <p v-if="subtitle" class="mt-1 text-sm text-slate-500">{{ subtitle }}</p>
        <slot />
      </div>
    </main>
  </div>
</template>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd auth-web && npm run test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/components/AuthLayout.vue auth-web/tests/auth-layout.test.ts
git commit -m "feat: add auth-web branded layout"
```

---

### Task 8: 登录页

**Files:**
- Create: `auth-web/src/views/LoginView.vue`
- Test: `auth-web/tests/login-view.test.ts`

**Interfaces:**
- Consumes: `login()`、`navigate()`、`ApiError`、`<AuthLayout />`。
- Produces: `LoginView` 组件；账号/密码表单、401 时表单内提示、成功后顶层导航到 `next`。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/login-view.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from '../src/views/LoginView.vue'
import { ApiError } from '../src/api/client'
import { i18n, setLocale } from '../src/i18n'
import { login } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/api/auth', () => ({ login: vi.fn() }))
vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

async function fillAndSubmit(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[name="account"]').setValue('alice')
  await wrapper.get('input[name="password"]').setValue('alice-password')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('LoginView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(login).mockReset()
    vi.mocked(navigate).mockReset()
  })

  it('submits credentials and follows the next step', async () => {
    vi.mocked(login).mockResolvedValue({ next: '/organizations' })
    const wrapper = mount(LoginView, { global: { plugins: [i18n] } })

    await fillAndSubmit(wrapper)

    expect(login).toHaveBeenCalledWith('alice', 'alice-password')
    expect(navigate).toHaveBeenCalledWith('/organizations')
  })

  it('shows an inline error for rejected credentials', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError(401, 'invalid_credentials'))
    const wrapper = mount(LoginView, { global: { plugins: [i18n] } })

    await fillAndSubmit(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toBe('账号或密码不正确。')
    expect(navigate).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/views/LoginView.vue` 不存在

- [ ] **Step 3: 实现登录页**

创建 `auth-web/src/views/LoginView.vue`：

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { navigate } from '../navigation'

const { t } = useI18n()
const account = ref('')
const password = ref('')
const error = ref<string | null>(null)
const submitting = ref(false)

async function submit(): Promise<void> {
  error.value = null
  submitting.value = true
  try {
    const result = await login(account.value, password.value)
    navigate(result.next)
  } catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 401 ? t('login.invalid') : t('login.failed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout :title="t('login.title')" :subtitle="t('login.subtitle')">
    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <p v-if="error" role="alert"
         class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        {{ error }}
      </p>
      <label class="grid gap-1 text-sm font-medium text-slate-700">
        {{ t('login.account') }}
        <input v-model="account" name="account" autocomplete="username" required autofocus
               class="rounded-lg border border-slate-200 px-3 py-2 font-normal text-slate-900 outline-none focus:ring-2 focus:ring-indigo-500" />
      </label>
      <label class="grid gap-1 text-sm font-medium text-slate-700">
        {{ t('login.password') }}
        <input v-model="password" name="password" type="password" autocomplete="current-password" required
               class="rounded-lg border border-slate-200 px-3 py-2 font-normal text-slate-900 outline-none focus:ring-2 focus:ring-indigo-500" />
      </label>
      <button type="submit" :disabled="submitting"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
        {{ submitting ? t('login.submitting') : t('login.submit') }}
      </button>
    </form>
  </AuthLayout>
</template>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd auth-web && npm run test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/views/LoginView.vue auth-web/tests/login-view.test.ts
git commit -m "feat: add auth-web sign-in page"
```

---

### Task 9: 组织选择页

**Files:**
- Create: `auth-web/src/views/OrganizationsView.vue`
- Test: `auth-web/tests/organizations-view.test.ts`

**Interfaces:**
- Consumes: `listOrganizations()`、`selectOrganization()`、`navigate()`、`ApiError`。
- Produces: `OrganizationsView`；单组织自动绑定直接导航，多组织渲染单选项与「继续」，403 显示无组织提示。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/organizations-view.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrganizationsView from '../src/views/OrganizationsView.vue'
import { ApiError } from '../src/api/client'
import { i18n, setLocale } from '../src/i18n'
import { listOrganizations, selectOrganization } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/api/auth', () => ({ listOrganizations: vi.fn(), selectOrganization: vi.fn() }))
vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

describe('OrganizationsView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(listOrganizations).mockReset()
    vi.mocked(selectOrganization).mockReset()
    vi.mocked(navigate).mockReset()
  })

  it('navigates straight away when the server already bound a single organization', async () => {
    vi.mocked(listOrganizations).mockResolvedValue({
      organizations: [{ id: 30, name: 'Beta' }],
      next: '/oauth2/authorize?client_id=auth-web-public',
    })

    mount(OrganizationsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(navigate).toHaveBeenCalledWith('/oauth2/authorize?client_id=auth-web-public')
  })

  it('lists organizations and submits the selected one', async () => {
    vi.mocked(listOrganizations).mockResolvedValue({
      organizations: [{ id: 10, name: 'Alpha' }, { id: 20, name: 'Beta' }],
      next: null,
    })
    vi.mocked(selectOrganization).mockResolvedValue({ next: '/' })
    const wrapper = mount(OrganizationsView, { global: { plugins: [i18n] } })
    await flushPromises()

    const options = wrapper.findAll('input[name="orgId"]')
    expect(options).toHaveLength(2)
    await options[1].setValue()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(selectOrganization).toHaveBeenCalledWith(20)
    expect(navigate).toHaveBeenCalledWith('/')
  })

  it('explains when the account has no organization', async () => {
    vi.mocked(listOrganizations).mockRejectedValue(new ApiError(403, 'access_denied'))
    const wrapper = mount(OrganizationsView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('当前账号没有可用的组织，请联系管理员。')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/views/OrganizationsView.vue` 不存在

- [ ] **Step 3: 实现组织选择页**

创建 `auth-web/src/views/OrganizationsView.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { listOrganizations, selectOrganization, type OrganizationView } from '../api/auth'
import { ApiError } from '../api/client'
import { navigate } from '../navigation'

const { t } = useI18n()
const organizations = ref<OrganizationView[]>([])
const selected = ref<number | null>(null)
const error = ref<string | null>(null)
const submitting = ref(false)

onMounted(async () => {
  try {
    const result = await listOrganizations()
    if (result.next) {
      navigate(result.next)
      return
    }
    organizations.value = result.organizations
    selected.value = result.organizations[0]?.id ?? null
  } catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 403
      ? t('organizations.denied')
      : t('organizations.failed')
  }
})

async function submit(): Promise<void> {
  if (selected.value === null) return
  submitting.value = true
  try {
    const result = await selectOrganization(selected.value)
    navigate(result.next)
  } catch {
    error.value = t('organizations.failed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout :title="t('organizations.title')" :subtitle="t('organizations.subtitle')">
    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <p v-if="error" role="alert"
         class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        {{ error }}
      </p>
      <label v-for="organization in organizations" :key="organization.id"
             class="flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 text-sm"
             :class="organization.id === selected
               ? 'border-indigo-600 bg-indigo-50 text-indigo-900'
               : 'border-slate-200 text-slate-700'">
        <input v-model="selected" type="radio" name="orgId" :value="organization.id" class="sr-only" />
        <span class="h-2 w-2 rounded-full"
              :class="organization.id === selected ? 'bg-indigo-600' : 'border border-slate-300 bg-white'"></span>
        {{ organization.name }}
      </label>
      <button type="submit" :disabled="submitting || selected === null"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
        {{ t('organizations.submit') }}
      </button>
    </form>
  </AuthLayout>
</template>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd auth-web && npm run test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/views/OrganizationsView.vue auth-web/tests/organizations-view.test.ts
git commit -m "feat: add auth-web organization picker"
```

---

### Task 10: 授权确认页与表单提交

**Files:**
- Create: `auth-web/src/consentForm.ts`、`auth-web/src/views/ConsentView.vue`
- Test: `auth-web/tests/consent.test.ts`

**Interfaces:**
- Consumes: `getConsent()`、`<AuthLayout />`、`vue-router` 的 `useRoute()`。
- Produces: `submitConsent({clientId, state, scopes})`（构造并提交隐藏表单到 `/oauth2/authorize`）、`ConsentView`。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/consent.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ConsentView from '../src/views/ConsentView.vue'
import { submitConsent } from '../src/consentForm'
import { getConsent } from '../src/api/auth'
import { i18n, setLocale } from '../src/i18n'

vi.mock('../src/api/auth', () => ({ getConsent: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({
    query: { client_id: 'auth-web-public', scope: ['openid', 'profile', 'weather:read'], state: 'test-state' },
  }),
}))

describe('submitConsent', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
  })

  it('posts client_id, state and every approved scope', () => {
    submitConsent({ clientId: 'auth-web-public', state: 'test-state', scopes: ['profile', 'weather:read'] })

    const form = document.forms[0]
    expect(form.method).toBe('post')
    expect(form.getAttribute('action')).toBe('/oauth2/authorize')
    const fields = Array.from(form.elements).map((element) => {
      const input = element as HTMLInputElement
      return [input.name, input.value]
    })
    expect(fields).toEqual([
      ['client_id', 'auth-web-public'],
      ['state', 'test-state'],
      ['scope', 'profile'],
      ['scope', 'weather:read'],
    ])
  })
})

describe('ConsentView', () => {
  beforeEach(() => {
    setLocale('zh')
    document.body.innerHTML = ''
    vi.mocked(getConsent).mockReset()
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
  })

  it('lists the requested scopes and submits the approved ones', async () => {
    vi.mocked(getConsent).mockResolvedValue({
      clientId: 'auth-web-public',
      clientName: 'Auth Web Client',
      scopes: ['profile', 'weather:read'],
      state: 'test-state',
      organization: { id: 10, name: 'Alpha' },
      user: { account: 'alice', name: 'Alice' },
    })
    const wrapper = mount(ConsentView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(getConsent).toHaveBeenCalledWith({
      clientId: 'auth-web-public',
      scopes: ['openid', 'profile', 'weather:read'],
      state: 'test-state',
    })
    expect(wrapper.text()).toContain('Auth Web Client')
    expect(wrapper.text()).toContain('Alpha')
    expect(wrapper.text()).toContain('读取天气数据')

    await wrapper.findAll('input[name="scope"]')[1].setValue(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const fields = Array.from(document.forms[0].elements).map((element) => {
      const input = element as HTMLInputElement
      return [input.name, input.value]
    })
    expect(fields).toEqual([
      ['client_id', 'auth-web-public'],
      ['state', 'test-state'],
      ['scope', 'profile'],
    ])
  })
})
```

注意：第二个用例在组件内使用了真实的 `submitConsent`，因此通过 `document.forms[0]` 断言最终提交内容。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/consentForm`、`../src/views/ConsentView.vue` 不存在

- [ ] **Step 3: 实现表单提交与确认页**

创建 `auth-web/src/consentForm.ts`：

```ts
export interface ConsentSubmission {
  clientId: string
  state: string | null
  scopes: string[]
}

/**
 * Spring Authorization Server 的授权确认必须由浏览器顶层表单 POST 完成：
 * 端点会把浏览器 302 回客户端的 redirect_uri，fetch 无法完成这个跳转。
 * 该端点忽略 CSRF（SAS 对其全部端点调用 csrf.ignoringRequestMatchers），因此无需 _csrf 字段。
 */
export function submitConsent(submission: ConsentSubmission): void {
  const form = document.createElement('form')
  form.method = 'post'
  form.action = '/oauth2/authorize'
  form.style.display = 'none'

  const fields: [string, string][] = [['client_id', submission.clientId]]
  if (submission.state) fields.push(['state', submission.state])
  submission.scopes.forEach((scope) => fields.push(['scope', scope]))

  fields.forEach(([name, value]) => {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = name
    input.value = value
    form.appendChild(input)
  })

  document.body.appendChild(form)
  form.submit()
}
```

创建 `auth-web/src/views/ConsentView.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { getConsent, type ConsentState } from '../api/auth'
import { submitConsent } from '../consentForm'

const route = useRoute()
const { t } = useI18n()
const consent = ref<ConsentState | null>(null)
const approved = ref<string[]>([])
const error = ref<string | null>(null)

onMounted(async () => {
  const clientId = String(route.query.client_id ?? '')
  const rawScope = route.query.scope
  const scopes = (Array.isArray(rawScope) ? rawScope : rawScope ? [rawScope] : []).map(String)
  const state = route.query.state ? String(route.query.state) : null
  try {
    const result = await getConsent({ clientId, scopes, state })
    consent.value = result
    approved.value = [...result.scopes]
  } catch {
    error.value = t('consent.failed')
  }
})

function scopeLabel(scope: string): string {
  if (scope === 'profile') return t('scope.profile')
  if (scope === 'weather:read') return t('scope.weatherRead')
  return scope
}

function approve(): void {
  if (!consent.value) return
  submitConsent({
    clientId: consent.value.clientId,
    state: consent.value.state,
    scopes: approved.value,
  })
}
</script>

<template>
  <AuthLayout :title="t('consent.title')"
              :subtitle="consent ? t('consent.subtitle', { client: consent.clientName }) : undefined">
    <p v-if="error" role="alert"
       class="mt-6 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      {{ error }}
    </p>
    <form v-else-if="consent" class="mt-6 grid gap-4" @submit.prevent="approve">
      <p v-if="consent.organization"
         class="rounded-full bg-indigo-50 px-3 py-1 text-xs font-medium text-indigo-700">
        {{ t('consent.asOrganization', { organization: consent.organization.name }) }}
      </p>
      <fieldset class="grid gap-2">
        <label v-for="scope in consent.scopes" :key="scope"
               class="flex items-center gap-3 text-sm text-slate-700">
          <input v-model="approved" type="checkbox" name="scope" :value="scope"
                 class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
          <span>{{ scopeLabel(scope) }}</span>
        </label>
      </fieldset>
      <button type="submit"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500">
        {{ t('consent.approve') }}
      </button>
    </form>
  </AuthLayout>
</template>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd auth-web && npm run test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/consentForm.ts auth-web/src/views/ConsentView.vue auth-web/tests/consent.test.ts
git commit -m "feat: add auth-web consent page"
```

---

### Task 11: 已登录落地页

**Files:**
- Create: `auth-web/src/views/HomeView.vue`
- Test: `auth-web/tests/home-view.test.ts`

**Interfaces:**
- Consumes: `getSession()`、`logout()`、`navigate()`。
- Produces: `HomeView`；展示账号与组织，提供退出登录。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/home-view.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from '../src/views/HomeView.vue'
import { i18n, setLocale } from '../src/i18n'
import { getSession, logout } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/api/auth', () => ({ getSession: vi.fn(), logout: vi.fn() }))
vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

describe('HomeView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(getSession).mockReset()
    vi.mocked(logout).mockReset()
    vi.mocked(navigate).mockReset()
  })

  it('shows the signed-in account and organization', async () => {
    vi.mocked(getSession).mockResolvedValue({
      authenticated: true,
      user: { account: 'alice', name: 'Alice' },
      organization: { id: 10, name: 'Alpha' },
      pending: null,
    })
    const wrapper = mount(HomeView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('Alpha')
  })

  it('signs out and returns to the login route', async () => {
    vi.mocked(getSession).mockResolvedValue({
      authenticated: true,
      user: { account: 'alice', name: 'Alice' },
      organization: { id: 10, name: 'Alpha' },
      pending: null,
    })
    vi.mocked(logout).mockResolvedValue()
    const wrapper = mount(HomeView, { global: { plugins: [i18n] } })
    await flushPromises()

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(logout).toHaveBeenCalled()
    expect(navigate).toHaveBeenCalledWith('/login')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/views/HomeView.vue` 不存在

- [ ] **Step 3: 实现落地页**

创建 `auth-web/src/views/HomeView.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../components/AuthLayout.vue'
import { getSession, logout, type SessionState } from '../api/auth'
import { navigate } from '../navigation'

const { t } = useI18n()
const session = ref<SessionState | null>(null)

onMounted(async () => {
  session.value = await getSession()
  if (!session.value.authenticated) {
    navigate('/login')
  }
})

async function signOut(): Promise<void> {
  await logout()
  navigate('/login')
}
</script>

<template>
  <AuthLayout :title="t('home.title')" :subtitle="t('home.subtitle')">
    <div v-if="session?.user" class="mt-6 grid gap-4">
      <dl class="grid gap-3 rounded-lg border border-slate-200 p-4">
        <div>
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('home.account') }}</dt>
          <dd class="text-sm font-semibold text-slate-900">{{ session.user.account }}</dd>
        </div>
        <div>
          <dt class="text-xs uppercase tracking-wide text-slate-500">{{ t('home.organization') }}</dt>
          <dd class="text-sm font-semibold text-slate-900">{{ session.organization?.name ?? '—' }}</dd>
        </div>
      </dl>
      <button type="button"
              class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
              @click="signOut">
        {{ t('home.logout') }}
      </button>
    </div>
  </AuthLayout>
</template>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd auth-web && npm run test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/views/HomeView.vue auth-web/tests/home-view.test.ts
git commit -m "feat: add auth-web signed-in page"
```

---

### Task 12: 路由、导航守卫与应用接线

**Files:**
- Create: `auth-web/src/router.ts`
- Modify: `auth-web/src/App.vue`、`auth-web/src/main.ts`
- Test: `auth-web/tests/router-guard.test.ts`

**Interfaces:**
- Consumes: `getSession()`、`navigate()`、四个 `*View`。
- Produces: `createSessionGuard(load: () => Promise<SessionState>)`（可单测的守卫工厂）、`router`（`/`、`/login`、`/organizations`、`/consent`，history 模式）。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/router-guard.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createSessionGuard } from '../src/router'
import type { SessionState } from '../src/api/auth'
import { navigate } from '../src/navigation'

vi.mock('../src/navigation', () => ({ navigate: vi.fn() }))

function session(overrides: Partial<SessionState> = {}): SessionState {
  return {
    authenticated: true,
    user: { account: 'alice', name: 'Alice' },
    organization: { id: 10, name: 'Alpha' },
    pending: null,
    ...overrides,
  }
}

function route(path: string) {
  return { path } as never
}

describe('session guard', () => {
  beforeEach(() => vi.mocked(navigate).mockReset())

  it('sends anonymous visitors to the sign-in route', async () => {
    const guard = createSessionGuard(async () => session({ authenticated: false, user: null, organization: null }))

    expect(await guard(route('/organizations'))).toEqual({ path: '/login' })
    expect(await guard(route('/login'))).toBe(true)
  })

  it('sends signed-in users without an organization to the picker', async () => {
    const guard = createSessionGuard(async () => session({ organization: null }))

    expect(await guard(route('/'))).toEqual({ path: '/organizations' })
    expect(await guard(route('/organizations'))).toBe(true)
  })

  it('resumes a pending authorization request from / and /login', async () => {
    const guard = createSessionGuard(async () => session({ pending: '/oauth2/authorize?client_id=auth-web-public' }))

    expect(await guard(route('/'))).toBe(false)
    expect(navigate).toHaveBeenCalledWith('/oauth2/authorize?client_id=auth-web-public')
  })

  it('keeps bound users off the sign-in and picker routes', async () => {
    const guard = createSessionGuard(async () => session())

    expect(await guard(route('/login'))).toEqual({ path: '/' })
    expect(await guard(route('/organizations'))).toEqual({ path: '/' })
    expect(await guard(route('/consent'))).toBe(true)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd auth-web && npm run test`
Expected: FAIL —— `../src/router` 不存在

- [ ] **Step 3: 实现守卫与路由**

创建 `auth-web/src/router.ts`：

```ts
import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { getSession, type SessionState } from './api/auth'
import { navigate } from './navigation'

export type GuardResult = boolean | { path: string }

/**
 * 会话守卫：未登录去 /login；已登录未绑定组织去 /organizations；
 * 已有待处理授权请求时（仅在 / 与 /login 上）恢复该请求，避免打断组织与 consent 步骤。
 */
export function createSessionGuard(load: () => Promise<SessionState>) {
  return async (to: Pick<RouteLocationNormalized, 'path'>): Promise<GuardResult> => {
    const session = await load()
    if (!session.authenticated) {
      return to.path === '/login' ? true : { path: '/login' }
    }
    if (!session.organization) {
      return to.path === '/organizations' ? true : { path: '/organizations' }
    }
    if (session.pending && (to.path === '/' || to.path === '/login')) {
      navigate(session.pending)
      return false
    }
    if (to.path === '/login' || to.path === '/organizations') {
      return { path: '/' }
    }
    return true
  }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./views/HomeView.vue') },
    { path: '/login', component: () => import('./views/LoginView.vue') },
    { path: '/organizations', component: () => import('./views/OrganizationsView.vue') },
    { path: '/consent', component: () => import('./views/ConsentView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(createSessionGuard(getSession))
```

替换 `auth-web/src/App.vue`：

```vue
<template>
  <RouterView />
</template>
```

替换 `auth-web/src/main.ts`：

```ts
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import './assets/main.css'

createApp(App).use(i18n).use(router).mount('#app')
```

- [ ] **Step 4: 运行测试与构建确认通过**

Run: `cd auth-web && npm run test && npm run build`
Expected: PASS，`dist/` 生成

- [ ] **Step 5: 提交**

```bash
git add auth-web/src/router.ts auth-web/src/App.vue auth-web/src/main.ts auth-web/tests/router-guard.test.ts
git commit -m "feat: wire auth-web router and session guard"
```

---

### Task 13: 后端安全收敛、SPA fallback 与入口点分流

**Files:**
- Modify: `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java`
- Create: `auth-server/src/main/java/com/example/auth/web/SpaForwardController.java`
- Delete: `auth-server/src/test/java/com/example/auth/web/OrganizationAuthorizationFlowTest.java`
- Create: `auth-server/src/test/java/com/example/auth/web/AuthenticationEntryPointTest.java`
- Modify: `auth-server/src/test/java/com/example/auth/security/TokenLifecycleSecurityTest.java`

**Interfaces:**
- Consumes: Task 1-12 的 API 与 SPA 路由。
- Produces: `/`、`/login`、`/organizations`、`/consent` 转发到 `index.html`（`Cache-Control: no-cache`）；未认证 `/api/**` 返回 401，未认证 HTML 请求 302 `/login`；应用链 CSRF 改为 cookie 仓库 + 明文处理器。

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/web/AuthenticationEntryPointTest.java`：

```java
package com.example.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口点分流：API 未认证返回 401 JSON，HTML 请求 302 到 SPA 的 /login；
 * 组织绑定后能继续被缓存的授权请求。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class AuthenticationEntryPointTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedAuthorizationRequestRedirectsToLogin() throws Exception {
        this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedApiRequestsAreRejectedWith401() throws Exception {
        this.mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bindingTheOrganizationResumesThePendingAuthorizationRequest() throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isOk());

        MvcResult selection = this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":20}"))
                .andExpect(status().isOk())
                .andReturn();
        String next = this.objectMapper.readTree(selection.getResponse().getContentAsString())
                .path("next").asText();
        assertThat(next).contains("/oauth2/authorize");

        MvcResult resumed = this.mockMvc.perform(authorizeRequest().session(session)).andReturn();
        String consentRedirect = resumed.getResponse().getRedirectedUrl();
        assertThat(consentRedirect).isNotNull();
        assertThat(java.net.URI.create(consentRedirect).getPath()).endsWith("/consent");
    }

    private static MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", "auth-web-public")
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/auth-server")
                .queryParam("state", "test-state")
                .queryParam("code_challenge", "0123456789012345678901234567890123456789012")
                .queryParam("code_challenge_method", "S256");
    }
}
```

创建 `auth-server/src/test/java/com/example/auth/web/SpaRoutingTest.java`：

```java
package com.example.auth.web;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPA 外壳：客户端路由转发到 index.html；构建过前端时校验产物内容。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SpaRoutingTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clientRoutesForwardToTheSpaShell() throws Exception {
        for (String path : List.of("/", "/login", "/organizations", "/consent")) {
            this.mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        }
    }

    @Test
    void builtSpaShellIsServedFromStaticResources() throws Exception {
        Assumptions.assumeTrue(getClass().getResource("/static/index.html") != null,
                "frontend bundle is not on the classpath; run without -DskipFrontend to build it");

        this.mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"app\"")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server test -Dtest='AuthenticationEntryPointTest,SpaRoutingTest' -DfailIfNoTests=false`
Expected: FAIL —— `/api/organizations` 目前重定向到 `/login`（不是 401）、四个 SPA 路由返回 404

- [ ] **Step 3: 实现 fallback 控制器与安全收敛**

创建 `auth-server/src/main/java/com/example/auth/web/SpaForwardController.java`：

```java
package com.example.auth.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把 SPA 的客户端路由转发到前端构建产物；外壳本身没有数据，数据接口仍然逐条鉴权。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/organizations", "/consent"})
    String spa(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        return "forward:/index.html";
    }
}
```

把 `SecurityConfig.applicationSecurityFilterChain` 整体替换为以下实现（并补 import）：

```java
    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http,
                                                       SecurityContextRepository securityContextRepository) throws Exception {
        http.securityContext((securityContext) -> securityContext
                .securityContextRepository(securityContextRepository));
        http.authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/", "/login", "/organizations", "/consent", "/assets/**", "/favicon.ico",
                        "/api/auth/session", "/api/auth/login", "/error", "/actuator/health").permitAll()
                .anyRequest().authenticated());
        // SPA 场景：cookie 里的原始 token 直接作为 X-XSRF-TOKEN 发送，因此使用明文处理器。
        http.csrf((csrf) -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.exceptionHandling((exceptions) -> exceptions
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        (request) -> request.getRequestURI().startsWith("/api/"))
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                        htmlRequests()));
        // 登出改由 POST /api/auth/logout 处理，避免保留第二套表单登出端点。
        http.logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static MediaTypeRequestMatcher htmlRequests() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }
```

需要新增的 import：

```java
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import java.util.Set;
```

同时删除 `formLogin(...)` 配置块。

- [ ] **Step 4: 迁移旧测试**

删除 `auth-server/src/test/java/com/example/auth/web/OrganizationAuthorizationFlowTest.java`（其覆盖已被 `AuthApiControllerTest`、`OrganizationApiControllerTest`、`AuthenticationEntryPointTest` 取代）。

把 `TokenLifecycleSecurityTest` 中的 `customPagesRejectRequestsWithoutACsrfToken` 方法整体删除（该覆盖由 `AuthApiControllerTest.loginWithoutACsrfTokenIsRejected` 承担），并移除不再使用的 `post`/`csrf` import。

- [ ] **Step 5: 运行后端测试确认通过**

Run: `mvn -pl auth-server test`
Expected: PASS（全部测试类）

- [ ] **Step 6: 提交**

```bash
git add -A auth-server/src/main/java/com/example/auth/config/SecurityConfig.java auth-server/src/main/java/com/example/auth/web/SpaForwardController.java auth-server/src/test/java/com/example/auth
git commit -m "refactor: serve the SPA shell and split API/HTML entry points"
```

---

### Task 14: 移除 Thymeleaf

**Files:**
- Delete: `auth-server/src/main/java/com/example/auth/web/LoginController.java`
- Delete: `auth-server/src/main/java/com/example/auth/web/OrganizationController.java`
- Delete: `auth-server/src/main/resources/templates/login.html`
- Delete: `auth-server/src/main/resources/templates/organizations.html`
- Modify: `auth-server/pom.xml`（移除 `spring-boot-starter-thymeleaf`）

**Interfaces:**
- Consumes: Task 13 已完成 HTML 页面到 SPA 的切换。
- Produces: 仓库中不再有 Thymeleaf 依赖与模板。

- [ ] **Step 1: 删除旧实现**

```bash
rm auth-server/src/main/java/com/example/auth/web/LoginController.java
rm auth-server/src/main/java/com/example/auth/web/OrganizationController.java
rm auth-server/src/main/resources/templates/login.html
rm auth-server/src/main/resources/templates/organizations.html
rmdir auth-server/src/main/resources/templates
```

- [ ] **Step 2: 移除 pom 依赖**

从 `auth-server/pom.xml` 删除：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
```

- [ ] **Step 3: 运行测试确认通过**

Run: `mvn -pl auth-server test`
Expected: PASS（无 Thymeleaf 依赖仍能编译与通过）

- [ ] **Step 4: 提交**

```bash
git add -A auth-server/src/main/java/com/example/auth/web auth-server/src/main/resources/templates auth-server/pom.xml
git commit -m "chore: drop thymeleaf now that the SPA serves the pages"
```

---

### Task 15: Maven 集成前端构建

**Files:**
- Modify: `auth-server/pom.xml`

**Interfaces:**
- Consumes: `auth-web/package.json` 与 `package-lock.json`（Task 5 生成）。
- Produces: `mvn -pl auth-server verify` 自动构建前端并把 `dist/` 复制到 `target/classes/static`；`-DskipFrontend=true` 同时跳过构建与拷贝。

- [ ] **Step 1: 修改 pom**

在 `<properties>` 中加入：

```xml
        <skipFrontend>false</skipFrontend>
        <node.version>v22.14.0</node.version>
        <npm.version>10.9.2</npm.version>
        <frontend-maven-plugin.version>1.15.1</frontend-maven-plugin.version>
```

在 `<build><plugins>` 中，**放到 `spring-boot-maven-plugin` 之前**（同一 phase 内按声明顺序执行，前端必须先构建再拷贝）：

```xml
            <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>${frontend-maven-plugin.version}</version>
                <configuration>
                    <workingDirectory>../auth-web</workingDirectory>
                    <installDirectory>${project.build.directory}/frontend</installDirectory>
                    <skip>${skipFrontend}</skip>
                </configuration>
                <executions>
                    <execution>
                        <id>install-node-and-npm</id>
                        <goals>
                            <goal>install-node-and-npm</goal>
                        </goals>
                        <configuration>
                            <nodeVersion>${node.version}</nodeVersion>
                            <npmVersion>${npm.version}</npmVersion>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm-ci</id>
                        <goals>
                            <goal>npm</goal>
                        </goals>
                        <configuration>
                            <arguments>ci</arguments>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm-build</id>
                        <goals>
                            <goal>npm</goal>
                        </goals>
                        <configuration>
                            <arguments>run build</arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-frontend</id>
                        <phase>generate-resources</phase>
                        <goals>
                            <goal>copy-resources</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                            <resources>
                                <resource>
                                    <directory>../auth-web/dist</directory>
                                    <filtering>false</filtering>
                                </resource>
                            </resources>
                            <skip>${skipFrontend}</skip>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
```

若国内网络下载 Node 缓慢，可在 CI/本地设置镜像：

```bash
export NODE_DOWNLOAD_ROOT=https://npmmirror.com/mirrors/node
```

- [ ] **Step 2: 验证含前端构建的完整构建**

Run: `mvn -pl auth-server verify`
Expected: PASS；日志中出现 `Installing node`、`npm ci`、`vite build`；`SpaRoutingTest.builtSpaShellIsServedFromStaticResources` 实际执行（不再跳过）

- [ ] **Step 3: 验证跳过开关与产物位置**

Run: `mvn -pl auth-server verify -DskipFrontend=true`
Expected: PASS，且不出现 npm 输出

Run: `unzip -l auth-server/target/auth-server-0.0.1-SNAPSHOT.jar | grep 'BOOT-INF/classes/static/index.html'`
Expected: 命中一行（默认构建时产物已在 jar 内）

- [ ] **Step 4: 提交**

```bash
git add auth-server/pom.xml
git commit -m "build: package the vue bundle into the auth-server jar"
```

---

### Task 16: 文档、忽略规则与端到端验收

**Files:**
- Modify: `.gitignore`、`auth-server/README.md`、`README.md`、`docs/auth-server-testing.md`

**Interfaces:**
- Consumes: 前 15 个任务的成果。
- Produces: 与实现一致的运行/验收文档；`node_modules`、`dist`、`.superpowers/` 不再进入版本库。

- [ ] **Step 1: 更新 .gitignore**

追加：

```gitignore
auth-web/node_modules/
auth-web/dist/
.superpowers/
```

- [ ] **Step 2: 更新 auth-server/README.md**

- 端点表中把 `GET /login`、`GET/POST /organizations`、`GET /oauth2/consent` 三行替换为：

```markdown
| `GET /`、`/login`、`/organizations`、`/consent` | Vue SPA 页面（转发到 `index.html`） |
| `GET /api/auth/session`、`POST /api/auth/login`、`POST /api/auth/logout` | SPA 登录会话 API |
| `GET /api/organizations`、`POST /api/organizations` | 组织选择 API（单组织自动绑定） |
| `GET /api/consent` | 授权确认页数据 API |
```

- 「本地运行」一节新增前端说明：

```markdown
4. 前端页面由 `auth-web/` 构建（`mvn -pl auth-server verify` 会自动执行）：
   - 只改后端时用 `-DskipFrontend=true` 跳过前端构建；
   - 只改前端时执行 `cd auth-web && npm install && npm run dev`，Vite（5173）会把
     `/api`、`/oauth2`、`/.well-known`、`/userinfo`、`/actuator` 代理到 `8083`；
   - 完整体验（含 MCP 客户端发起的授权流程）请先构建前端再启动 8083。
```

- 新增 Nginx 部署示例一节：

```markdown
## 可选：用 Nginx 托管 SPA（同源部署）

```nginx
root /srv/auth-web/dist;
location / { try_files $uri /index.html; }
location ~ ^/(api|oauth2|\.well-known|userinfo|actuator)/ {
    proxy_pass http://127.0.0.1:8083;
    proxy_set_header Host $host;
}
```

浏览器视角仍是单一 origin，session cookie 与 CSRF 行为与打进 jar 时完全一致。
```

- [ ] **Step 3: 更新根 README.md 与 docs/auth-server-testing.md**

根 `README.md` 的「Modules」新增一行，并在 auth-server 段落说明前端工程：

```markdown
- `auth-web` — auth-server 的 Vue 3 前端工程（构建产物打进 auth-server jar，也可由 Nginx 同源托管）
```

`docs/auth-server-testing.md` 新增手动验收步骤：

```markdown
## SPA 页面验收（手动）

1. `mvn -pl auth-server verify`（构建前端）后启动 8083。
2. 访问 `http://localhost:8083/login`：应看到品牌分栏布局的登录页，右上角可切换中文/EN。
3. 用 MCP Inspector 走完整授权码流程：登录 → 组织选择 → 授权确认 → 回到客户端拿到 token。
4. 刷新 `/login`、`/organizations`、`/consent`、`/` 任意一次都不应出现 404。
5. 浏览器 DevTools 中确认所有请求同源、无 CORS 预检；窄屏（< 768px）下品牌分栏折叠。
```

- [ ] **Step 4: 端到端验收**

Run: `mvn -pl auth-server verify`
Run: `mvn -pl auth-server verify -DskipFrontend=true`
Run: `DB_URL='jdbc:mysql://localhost:3306/auth?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' DB_USERNAME=root DB_PASSWORD='' AUTH_KEYSTORE_PATH=file:./local/auth-server-local.p12 AUTH_KEYSTORE_PASSWORD=changeit AUTH_KEYSTORE_ALIAS=auth-server-local mvn -pl auth-server spring-boot:run`
Run（另一个终端）: `scripts/auth-server-smoke.sh`
Expected: 三条构建/测试通过；smoke 脚本全部 `ok:`；浏览器按 Step 3 的手动步骤通过。

- [ ] **Step 5: 提交**

```bash
git add .gitignore auth-server/README.md README.md docs/auth-server-testing.md
git commit -m "docs: document the SPA build and acceptance flow"
```

---

## Self-Review

**Spec 覆盖检查**

| Spec 章节 | 对应任务 |
| --- | --- |
| 第 2 节 决策 1/2/3 | Task 12-15（同源、JSON API、jar 优先 + Nginx 示例） |
| 第 3 节 架构与部署形态 | Task 13（fallback 与外壳放行）、Task 15（打包）、Task 16（Nginx 示例） |
| 第 4 节 交互流程 | Task 1-4（登录/组织/consent）、Task 12（守卫恢复 pending） |
| 第 5 节 前端应用 | Task 5-12 |
| 第 6 节 API 契约 | Task 1-3、Task 13 |
| 第 7 节 安全设计 | Task 1（session 轮换）、Task 13（CSRF cookie + 明文处理器、入口点分流、禁用默认 logout） |
| 第 8 节 构建与开发流程 | Task 15、Task 16（Nginx 示例、gitignore） |
| 第 9 节 测试策略 | Task 1-3、13（后端）、Task 5-12（前端）、Task 16（手动验收） |
| 第 10 节 迁移与删除清单 | Task 4、13、14 |
| 第 11 节 验收标准 | Task 15 Step 2-3、Task 16 Step 4 |

**占位符检查**：计划中不包含 TBD/TODO；每个代码步骤都给出可直接套用的完整代码或精确 diff 指令。

**类型一致性检查**：

- 后端 `UserView`/`OrganizationView`/`ApiError` 在 Task 1 定义，Task 2/3 复用，字段名与前端 `src/api/auth.ts` 的 `UserView`/`OrganizationView` 一致。
- `next` 在后端始终是字符串；前端 `listOrganizations()` 的 `next` 允许 `null`，与 `GET /api/organizations` 的响应对应。
- `consentPayload()` 返回的 JSON 字段 `clientId`/`state`/`scopes` 与 `submitConsent()` 的入参一一对应。
- 守卫工厂 `createSessionGuard(load)` 的返回类型 `GuardResult` 同时覆盖 `true`、`false` 与重定向对象三种情况。

## Execution Handoff

按 writing-plans 的约定，执行方式二选一：

1. **Subagent-Driven（推荐）**：每个任务派一个新的 subagent 实现，任务之间做 review；适合本计划这种任务边界清晰的场景。
2. **Inline Execution**：在当前会话按 executing-plans 批量执行，带检查点。
