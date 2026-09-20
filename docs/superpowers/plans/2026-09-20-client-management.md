# 客户端管理（Client Management）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 auth-server 内新增平台管理员专用的 `/api/admin/clients` 管理 API，并在 auth-web 中提供列表、创建、编辑、轮换 secret、启停、删除的完整管理界面，所有写入走 `RegisteredClientRepository` 并留下审计。

**Architecture:** 管理面与协议端点共用 auth-server 与同一个 MySQL schema，但走独立的 `/api/admin/**` 路径与 `ROLE_PLATFORM_ADMIN` 权限；写入通过 `JdbcRegisteredClientRepository` 原始 bean，协议端点继续使用带 TTL 覆盖与禁用过滤的 `ConfiguredRegisteredClients` 装饰器。前端在 auth-web 内新增三个管理页面，复用现有 session、CSRF、i18n 与布局。

**Tech Stack:** Spring Boot 4.0.8、Spring Security 7.0.7（含 `spring-security-oauth2-authorization-server` 7.0.7）、JUnit 5 + MockMvc + MySQL 8 Testcontainers、JdbcTemplate、Flyway、Vue 3 + TypeScript + Vue Router + vue-i18n + Vitest。

**Spec:** `docs/superpowers/specs/2026-09-20-client-management-design.md`

## Global Constraints

- OAuth 2.1 / OIDC 协议行为、令牌格式、`oauth2_registered_client` 既有表结构与 `weather-mcp-server` 均不变；不引入 CORS。
- 管理 API 只走应用安全链（Order 2）：`/api/admin/**` 需要 `ROLE_PLATFORM_ADMIN`；未登录 401，越权 403 `{"error":"access_denied"}`。
- 平台管理员账号来自 `auth.admin.accounts`（逗号分隔）；测试 profile 固定为 `alice`。
- client_id 创建后不可变；secret 由服务端生成，`{bcrypt}` 前缀存储，明文只在创建/轮换响应出现一次；列表、详情、日志、审计都不含 secret。
- 禁用状态存放在 `client_settings` 的 `com.example.auth.client.enabled`（Boolean，缺省即启用）。
- 审计 action 固定为 `CREATE`、`UPDATE`、`ROTATE_SECRET`、`ENABLE`、`DISABLE`、`DELETE`，只记录字段名不记录值。
- 错误码：`client_not_found`（404）、`client_id_taken`（409）、`invalid_client_metadata`（400）、`access_denied`（403）。
- 后端测试命令：`mvn -pl auth-server -DskipFrontend=true -Dtest=<Class> test`；模块全量：`mvn -pl auth-server verify`；前端：`npm --prefix auth-web test`、`npm --prefix auth-web run build`。
- 页面文案中英双语（vue-i18n，`zh` 为 fallback）；每个任务结束提交一次小粒度 commit，提交信息使用任务给出的原文。

---

### Task 1: 平台管理员权限与会话字段

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/security/PlatformAdmin.java`
- Create: `auth-server/src/main/java/com/example/auth/web/ApiAccessDeniedHandler.java`
- Modify: `auth-server/src/main/java/com/example/auth/config/AuthServerProperties.java`
- Modify: `auth-server/src/main/java/com/example/auth/security/IdentityAuthenticationProvider.java`
- Modify: `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java`
- Modify: `auth-server/src/main/java/com/example/auth/web/AuthApiController.java`
- Modify: `auth-server/src/main/resources/application.yml`
- Modify: `auth-server/src/test/resources/application-test.yml`
- Modify: `auth-server/src/test/java/com/example/auth/config/KeyStoreConfigTest.java`
- Test: `auth-server/src/test/java/com/example/auth/web/PlatformAdminAccessTest.java`

**Interfaces:**
- Consumes: 现有 `IdentityRepository`、`PasswordEncoder`、`SecurityFilterChain`、`MockMvc` + Testcontainers 测试基建。
- Produces（后续任务依赖）：
  - `PlatformAdmin.AUTHORITY` 常量，值 `"ROLE_PLATFORM_ADMIN"`。
  - `AuthServerProperties` 新增组件 `Admin(List<String> accounts)`，访问器 `properties.admin().accounts()`。
  - `SessionResponse(boolean authenticated, UserView user, OrganizationView organization, String pending, boolean platformAdmin)`。
  - `/api/admin/**` 未登录 401、非管理员 403 JSON、管理员放行。

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/web/PlatformAdminAccessTest.java`：

```java
package com.example.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台管理员访问控制：只有 auth.admin.accounts 命中的账号能访问 /api/admin/**。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class PlatformAdminAccessTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousAdminRequestsAreRejectedWith401() throws Exception {
        this.mockMvc.perform(get("/api/admin/clients"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminsAreRejectedWith403Json() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "bob", "bob-password");

        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    @Test
    void platformAdminsPassTheSecurityFilter() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "alice", "alice-password");

        // 控制器尚未实现时是 404；关键是不能再被 401/403 拦截。
        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void sessionExposesThePlatformAdminFlag() throws Exception {
        MockHttpSession adminSession = new MockHttpSession();
        login(adminSession, "alice", "alice-password");
        this.mockMvc.perform(get("/api/auth/session").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true));

        MockHttpSession userSession = new MockHttpSession();
        login(userSession, "bob", "bob-password");
        this.mockMvc.perform(get("/api/auth/session").session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(false));
    }

    private void login(MockHttpSession session, String account, String password) throws Exception {
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=PlatformAdminAccessTest test`

Expected: FAIL —— `nonAdminsAreRejectedWith403Json` 得到 404 而不是 403；`sessionExposesThePlatformAdminFlag`
因 `$.platformAdmin` 不存在失败。

- [ ] **Step 3: 新增权限常量与拒绝处理器**

创建 `auth-server/src/main/java/com/example/auth/security/PlatformAdmin.java`：

```java
package com.example.auth.security;

/**
 * 平台管理员的授权标记；账号来源见 {@code auth.admin.accounts}。
 */
public final class PlatformAdmin {

    public static final String AUTHORITY = "ROLE_PLATFORM_ADMIN";

    private PlatformAdmin() {
    }
}
```

创建 `auth-server/src/main/java/com/example/auth/web/ApiAccessDeniedHandler.java`：

```java
package com.example.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 已登录但越权的 API 请求返回与 {@link ApiError} 一致的 JSON，而不是空响应体。
 */
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + ApiError.ACCESS_DENIED + "\"}");
    }
}
```

- [ ] **Step 4: 扩展配置属性**

把 `auth-server/src/main/java/com/example/auth/config/AuthServerProperties.java` 改为：

```java
package com.example.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * Externalized configuration for the auth-server ({@code auth.*}).
 *
 * <p>The issuer, the signing keystore and the token lifetimes are deployment
 * inputs; nothing here falls back to a generated value.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthServerProperties(String issuer, Keystore keystore, Oauth oauth, Resources resources, Admin admin) {

    public AuthServerProperties {
        if (resources == null) {
            resources = new Resources("http://localhost:8081/mcp");
        }
        if (admin == null) {
            admin = new Admin(List.of());
        }
    }

    /**
     * External PKCS#12/JKS keystore holding the RSA key that signs tokens.
     */
    public record Keystore(String location, String password, String type, String alias) {
    }

    /**
     * OAuth 2.1 / OIDC lifetimes; the defaults in {@code application.yml} mirror the
     * design spec (code 5m, access 10m, id 10m, refresh 30d, session idle 30m).
     */
    public record Oauth(Duration authorizationCodeTtl, Duration accessTokenTtl, Duration idTokenTtl,
                        Duration refreshTokenTtl, Duration sessionIdleTimeout) {
    }

    /**
     * Resource identifiers that tokens are audience-bound to (RFC 9068). The
     * weather MCP server validates that {@code aud} matches its own resource
     * identifier.
     */
    public record Resources(String weatherMcp) {
    }

    /**
     * Platform administrator accounts allowed to call {@code /api/admin/**}.
     */
    public record Admin(List<String> accounts) {

        public Admin {
            accounts = accounts == null ? List.of()
                    : accounts.stream().filter(StringUtils::hasText).map(String::trim).toList();
        }
    }
}
```

- [ ] **Step 5: 授予权限、暴露会话字段、收紧安全链**

修改 `auth-server/src/main/java/com/example/auth/security/IdentityAuthenticationProvider.java`：注入
`AuthServerProperties`，并在认证成功时追加 `Role`：

```java
    private final IdentityRepository identityRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthServerProperties properties;

    public IdentityAuthenticationProvider(IdentityRepository identityRepository, PasswordEncoder passwordEncoder,
                                          AuthServerProperties properties) {
        this.identityRepository = identityRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }
```

把 `authenticate` 末尾构造 `Authentication` 的代码替换为：

```java
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.account(), user.name());
        // Spring Authorization Server reads the authentication time from the
        // password factor when it issues an ID token.
        FactorGrantedAuthority passwordFactor = FactorGrantedAuthority
                .withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now())
                .build();
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(passwordFactor);
        if (this.properties.admin().accounts().contains(user.account())) {
            authorities.add(new SimpleGrantedAuthority(PlatformAdmin.AUTHORITY));
        }
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
```

需要补充 import：`com.example.auth.config.AuthServerProperties`、`java.util.ArrayList`、
`org.springframework.security.core.GrantedAuthority`、`org.springframework.security.core.authority.SimpleGrantedAuthority`。

修改 `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java` 的 `authorizeHttpRequests` 与
`exceptionHandling`：

```java
        http.authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/api/admin/**").hasAuthority(PlatformAdmin.AUTHORITY)
                .requestMatchers("/", "/login", "/organizations", "/consent", "/assets/**", "/favicon.ico",
                        "/index.html",
                        "/api/auth/session", "/api/auth/login", "/error", "/actuator/health").permitAll()
                .anyRequest().authenticated());
```

```java
        http.exceptionHandling((exceptions) -> exceptions
                .accessDeniedHandler(new ApiAccessDeniedHandler())
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        (request) -> request.getRequestURI().startsWith("/api/"))
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                        htmlRequests()));
```

需要 import `com.example.auth.security.PlatformAdmin`。

修改 `auth-server/src/main/java/com/example/auth/web/AuthApiController.java` 的 `session` 方法与响应
record：

```java
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
```

```java
    public record SessionResponse(boolean authenticated, UserView user, OrganizationView organization, String pending,
                                  boolean platformAdmin) {
    }
```

需要 import `com.example.auth.security.PlatformAdmin`。

- [ ] **Step 6: 增加配置项并修复既有测试构造**

在 `auth-server/src/main/resources/application.yml` 的 `auth:` 下增加：

```yaml
  admin:
    # 平台管理员账号（逗号分隔）；只有这些账号能访问 /api/admin/**
    accounts: ${AUTH_ADMIN_ACCOUNTS:}
```

在 `auth-server/src/test/resources/application-test.yml` 的 `auth:` 下增加：

```yaml
  admin:
    accounts: alice
```

把 `auth-server/src/test/java/com/example/auth/config/KeyStoreConfigTest.java` 第 57-58 行的构造改为
5 个参数（新增的 `admin` 传 `null`，由 compact constructor 归一为默认值）：

```java
        return new AuthServerProperties("http://localhost:8083",
                new AuthServerProperties.Keystore(location, password, type, alias), null, null, null);
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest='PlatformAdminAccessTest,AuthApiControllerTest,IdentityAuthenticationProviderTest' test`

Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/security/PlatformAdmin.java \
        auth-server/src/main/java/com/example/auth/web/ApiAccessDeniedHandler.java \
        auth-server/src/main/java/com/example/auth/config/AuthServerProperties.java \
        auth-server/src/main/java/com/example/auth/security/IdentityAuthenticationProvider.java \
        auth-server/src/main/java/com/example/auth/config/SecurityConfig.java \
        auth-server/src/main/java/com/example/auth/web/AuthApiController.java \
        auth-server/src/main/resources/application.yml \
        auth-server/src/test/resources/application-test.yml \
        auth-server/src/test/java/com/example/auth/config/KeyStoreConfigTest.java \
        auth-server/src/test/java/com/example/auth/web/PlatformAdminAccessTest.java
git commit -m "feat: add platform admin authorization"
```

---

### Task 2: V4 审计表迁移

**Files:**
- Create: `auth-server/src/main/resources/db/migration/V4__oauth2_registered_client_audit.sql`
- Modify: `auth-server/src/test/java/com/example/auth/AuthServerMySqlIntegrationTest.java`

**Interfaces:**
- Produces: 表 `oauth2_registered_client_audit(id, registered_client_id, client_id, action, actor_user_id, actor_account, changed_fields, created_at)`，供 Task 3 的 `ClientAuditRepository` 写入。
- Consumes: 现有 Flyway 迁移链 V1-V3。

- [ ] **Step 1: 写失败的测试**

把 `AuthServerMySqlIntegrationTest` 中的 `flywayCreatesTheThreeOAuthTables` 替换为：

```java
    @Test
    void flywayCreatesTheOAuthTablesIncludingClientAudit() {
        Integer tables = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('oauth2_registered_client', 'oauth2_authorization',
                                     'oauth2_authorization_consent', 'oauth2_registered_client_audit')
                """, Integer.class);
        assertThat(tables).isEqualTo(4);

        Integer migrations = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('1', '2', '3', '4') AND success = 1
                """, Integer.class);
        assertThat(migrations).isEqualTo(4);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=AuthServerMySqlIntegrationTest#flywayCreatesTheOAuthTablesIncludingClientAudit test`

Expected: FAIL —— 表数量是 3，迁移数量是 3。

- [ ] **Step 3: 新增迁移**

创建 `auth-server/src/main/resources/db/migration/V4__oauth2_registered_client_audit.sql`：

```sql
-- 客户端管理审计：记录平台管理员对 oauth2_registered_client 的每次变更。
-- 只记录字段名，不记录任何 secret；不设外键，客户端删除后审计仍然保留。

CREATE TABLE oauth2_registered_client_audit (
    id bigint NOT NULL AUTO_INCREMENT,
    registered_client_id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    action varchar(32) NOT NULL,
    actor_user_id bigint NOT NULL,
    actor_account varchar(64) NOT NULL,
    changed_fields varchar(500) DEFAULT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    KEY idx_registered_client_audit_client_id (client_id),
    KEY idx_registered_client_audit_created_at (created_at)
);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=AuthServerMySqlIntegrationTest test`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/resources/db/migration/V4__oauth2_registered_client_audit.sql \
        auth-server/src/test/java/com/example/auth/AuthServerMySqlIntegrationTest.java
git commit -m "feat: add registered client audit schema"
```

---

### Task 3: 管理面仓储、视图与禁用过滤

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementSettings.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/AuditAction.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientTypeResolver.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientSummaryView.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientDetailView.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientQueryRepository.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientAuditRepository.java`
- Modify: `auth-server/src/main/java/com/example/auth/config/AuthorizationServerConfig.java`
- Modify: `auth-server/src/main/java/com/example/auth/config/ConfiguredRegisteredClients.java`
- Test: `auth-server/src/test/java/com/example/auth/clientadmin/ClientAdminRepositoriesTest.java`

**Interfaces:**
- Consumes: `oauth2_registered_client`、`oauth2_registered_client_audit`（Task 2）、`AuthenticatedUser`。
- Produces（后续任务依赖）：
  - `ClientManagementSettings.ENABLED`、`isEnabled(RegisteredClient)`、`withEnabled(RegisteredClient, boolean)`。
  - `ClientSummaryView(String clientId, String clientName, String type, List<String> grantTypes, List<String> scopes, boolean enabled, Instant updatedAt, String updatedBy)`。
  - `ClientDetailView(...)` 及嵌套 `TokenSettingsView(String accessTokenTimeToLive, String refreshTokenTimeToLive, String authorizationCodeTimeToLive, String idTokenSignatureAlgorithm, String accessTokenFormat)`。
  - `ClientQueryRepository.findAll(String)`、`deleteAuthorizationsAndConsents(String)`、`deleteRegisteredClient(String)`。
  - `ClientAuditRepository.record(String registeredClientId, String clientId, AuditAction action, AuthenticatedUser actor, List<String> changedFields)`。
  - `ClientTypeResolver.resolve(RegisteredClient)` 与 `resolve(Collection<String>, Collection<String>)`。
  - Spring bean：`JdbcRegisteredClientRepository`（原始）与 `@Primary RegisteredClientRepository`（装饰器）。

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/clientadmin/ClientAdminRepositoriesTest.java`：

```java
package com.example.auth.clientadmin;

import com.example.auth.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理面仓储：列表读模型、审计写入、禁用过滤与清理语义。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ClientAdminRepositoriesTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private JdbcRegisteredClientRepository rawRepository;

    @Autowired
    private RegisteredClientRepository decoratedRepository;

    @Autowired
    private ClientQueryRepository clientQueryRepository;

    @Autowired
    private ClientAuditRepository clientAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void summariesCarryTypeEnabledStateAndAuditMetadata() {
        RegisteredClient client = machineClient("reporting-service");
        this.rawRepository.save(client);
        this.clientAuditRepository.record(client.getId(), client.getClientId(), AuditAction.CREATE,
                new AuthenticatedUser(1L, "alice", "Alice"), List.of());

        List<ClientSummaryView> summaries = this.clientQueryRepository.findAll("report");

        assertThat(summaries).hasSize(1);
        ClientSummaryView summary = summaries.get(0);
        assertThat(summary.clientId()).isEqualTo("reporting-service");
        assertThat(summary.type()).isEqualTo("machine");
        assertThat(summary.enabled()).isTrue();
        assertThat(summary.updatedBy()).isEqualTo("alice");
        assertThat(summary.updatedAt()).isNotNull();
    }

    @Test
    void disabledClientsAreHiddenFromTheDecoratedRepository() {
        RegisteredClient client = ClientManagementSettings.withEnabled(machineClient("disabled-client"), false);
        this.rawRepository.save(client);

        assertThat(this.rawRepository.findByClientId("disabled-client")).isNotNull();
        assertThat(this.decoratedRepository.findByClientId("disabled-client")).isNull();
        assertThat(this.decoratedRepository.findById(client.getId())).isNull();
    }

    @Test
    void cleanupRemovesAuthorizationsAndConsents() {
        RegisteredClient client = machineClient("cleanup-client");
        this.rawRepository.save(client);
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID().toString(), client.getId(), "cleanup-client", "client_credentials");
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                VALUES (?, ?, ?)
                """, client.getId(), "1", "openid");

        this.clientQueryRepository.deleteAuthorizationsAndConsents(client.getId());

        assertThat(count("oauth2_authorization", client.getId())).isZero();
        assertThat(count("oauth2_authorization_consent", client.getId())).isZero();
        assertThat(this.clientQueryRepository.deleteRegisteredClient(client.getId())).isEqualTo(1);
        assertThat(this.rawRepository.findByClientId("cleanup-client")).isNull();
    }

    @Test
    void clientTypeResolverMatchesThePresets() {
        assertThat(ClientTypeResolver.resolve(List.of("authorization_code", "refresh_token"),
                List.of("client_secret_basic"))).isEqualTo("web");
        assertThat(ClientTypeResolver.resolve(List.of("client_credentials"),
                List.of("client_secret_basic"))).isEqualTo("machine");
        assertThat(ClientTypeResolver.resolve(List.of("authorization_code"), List.of("none"))).isEqualTo("public");
        assertThat(ClientTypeResolver.resolve(List.of("urn:example:grant"),
                List.of("client_secret_basic"))).isEqualTo("custom");
    }

    private static RegisteredClient machineClient(String clientId) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientIdIssuedAt(Instant.now())
                .clientSecret("{bcrypt}$2a$10$" + UUID.randomUUID().toString().replace("-", ""))
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("weather:read")
                .build();
    }

    private int count(String table, String registeredClientId) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE registered_client_id = ?",
                Integer.class, registeredClientId);
        return count == null ? 0 : count;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=ClientAdminRepositoriesTest test`

Expected: 编译失败（`ClientManagementSettings` 等类不存在）。

- [ ] **Step 3: 新增 settings 常量、枚举、类型推导与视图**

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementSettings.java`：

```java
package com.example.auth.clientadmin;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理面写入 client_settings 的私有 setting；框架的 withSettings 会原样往返未知 key。
 */
public final class ClientManagementSettings {

    public static final String ENABLED = "com.example.auth.client.enabled";

    private ClientManagementSettings() {
    }

    public static boolean isEnabled(RegisteredClient client) {
        Object enabled = client.getClientSettings().getSettings().get(ENABLED);
        return !Boolean.FALSE.equals(enabled);
    }

    public static RegisteredClient withEnabled(RegisteredClient client, boolean enabled) {
        Map<String, Object> settings = new HashMap<>(client.getClientSettings().getSettings());
        settings.put(ENABLED, enabled);
        return RegisteredClient.from(client)
                .clientSettings(ClientSettings.withSettings(settings).build())
                .build();
    }
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/AuditAction.java`：

```java
package com.example.auth.clientadmin;

public enum AuditAction {
    CREATE, UPDATE, ROTATE_SECRET, ENABLE, DISABLE, DELETE
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientTypeResolver.java`：

```java
package com.example.auth.clientadmin;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Collection;

/**
 * 展示类型由 grant types 与认证方式推导；匹配不到预设时返回 custom。
 */
public final class ClientTypeResolver {

    private ClientTypeResolver() {
    }

    public static String resolve(RegisteredClient client) {
        return resolve(
                client.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).toList(),
                client.getClientAuthenticationMethods().stream().map(ClientAuthenticationMethod::getValue).toList());
    }

    public static String resolve(Collection<String> grantTypes, Collection<String> authenticationMethods) {
        boolean publicClient = authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue());
        if (publicClient && grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())) {
            return "public";
        }
        if (grantTypes.contains(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())) {
            return "machine";
        }
        if (grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())) {
            return "web";
        }
        return "custom";
    }
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientSummaryView.java`：

```java
package com.example.auth.clientadmin;

import java.time.Instant;
import java.util.List;

public record ClientSummaryView(String clientId, String clientName, String type, List<String> grantTypes,
                                List<String> scopes, boolean enabled, Instant updatedAt, String updatedBy) {
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientDetailView.java`：

```java
package com.example.auth.clientadmin;

import java.time.Instant;
import java.util.List;

public record ClientDetailView(String clientId, String clientName, String type,
                               List<String> clientAuthenticationMethods, List<String> grantTypes,
                               List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> scopes,
                               boolean requireProofKey, boolean requireAuthorizationConsent,
                               Instant clientIdIssuedAt, Instant clientSecretExpiresAt, boolean enabled,
                               TokenSettingsView tokenSettings) {

    public record TokenSettingsView(String accessTokenTimeToLive, String refreshTokenTimeToLive,
                                    String authorizationCodeTimeToLive, String idTokenSignatureAlgorithm,
                                    String accessTokenFormat) {
    }
}
```

- [ ] **Step 4: 新增查询与审计仓储**

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientQueryRepository.java`：

```java
package com.example.auth.clientadmin;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 框架的 RegisteredClientRepository 没有 list/delete，这里补查询与清理；
 * SELECT 列表中不出现 client_secret 列。
 */
@Repository
public class ClientQueryRepository {

    private static final String FIND_ALL_SQL = """
            SELECT c.id, c.client_id, c.client_name, c.client_authentication_methods,
                   c.authorization_grant_types, c.scopes, c.client_settings,
                   a.created_at AS updated_at, a.actor_account AS updated_by
            FROM oauth2_registered_client c
            LEFT JOIN oauth2_registered_client_audit a
                   ON a.id = (SELECT MAX(a2.id) FROM oauth2_registered_client_audit a2 WHERE a2.client_id = c.client_id)
            """;

    private static final String ORDER_BY = " ORDER BY c.client_name, c.client_id";

    private static final ParameterizedTypeReference<Map<String, Object>> SETTINGS_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final RowMapper<ClientSummaryView> summaryMapper = (rs, rowNum) -> toSummary(rs);

    public ClientQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ClientSummaryView> findAll(String query) {
        if (query == null || query.isBlank()) {
            return this.jdbcTemplate.query(FIND_ALL_SQL + ORDER_BY, this.summaryMapper);
        }
        String like = "%" + query.trim() + "%";
        return this.jdbcTemplate.query(
                FIND_ALL_SQL + " WHERE c.client_id LIKE ? OR c.client_name LIKE ?" + ORDER_BY,
                this.summaryMapper, like, like);
    }

    public void deleteAuthorizationsAndConsents(String registeredClientId) {
        this.jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?",
                registeredClientId);
        this.jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?",
                registeredClientId);
    }

    public int deleteRegisteredClient(String registeredClientId) {
        return this.jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", registeredClientId);
    }

    private ClientSummaryView toSummary(ResultSet rs) throws SQLException {
        List<String> grantTypes = split(rs.getString("authorization_grant_types"));
        List<String> authenticationMethods = split(rs.getString("client_authentication_methods"));
        return new ClientSummaryView(
                rs.getString("client_id"),
                rs.getString("client_name"),
                ClientTypeResolver.resolve(grantTypes, authenticationMethods),
                grantTypes,
                split(rs.getString("scopes")),
                isEnabled(rs.getString("client_settings")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("updated_by"));
    }

    private boolean isEnabled(String clientSettingsJson) {
        if (clientSettingsJson == null || clientSettingsJson.isBlank()) {
            return true;
        }
        try {
            Map<String, Object> settings = this.jsonMapper.readValue(clientSettingsJson,
                    this.jsonMapper.getTypeFactory().constructType(SETTINGS_TYPE.getType()));
            return !Boolean.FALSE.equals(settings.get(ClientManagementSettings.ENABLED));
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Stored client_settings is not valid JSON", ex);
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter((part) -> !part.isEmpty()).toList();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientAuditRepository.java`：

```java
package com.example.auth.clientadmin;

import com.example.auth.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审计写入：只记录变更字段名，不记录任何值。
 */
@Repository
public class ClientAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClientAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String registeredClientId, String clientId, AuditAction action, AuthenticatedUser actor,
                       List<String> changedFields) {
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_registered_client_audit
                    (registered_client_id, client_id, action, actor_user_id, actor_account, changed_fields)
                VALUES (?, ?, ?, ?, ?, ?)
                """, registeredClientId, clientId, action.name(), actor.id(), actor.account(),
                changedFields == null || changedFields.isEmpty() ? null : String.join(",", changedFields));
    }
}
```

- [ ] **Step 5: 拆分仓储 bean 并在装饰器里过滤禁用客户端**

修改 `auth-server/src/main/java/com/example/auth/config/AuthorizationServerConfig.java` 的仓储 bean：

```java
    @Bean
    JdbcRegisteredClientRepository jdbcRegisteredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    @Primary
    RegisteredClientRepository registeredClientRepository(
            JdbcRegisteredClientRepository jdbcRegisteredClientRepository, AuthServerProperties properties) {
        return new ConfiguredRegisteredClients(jdbcRegisteredClientRepository, properties);
    }
```

需要 import `org.springframework.context.annotation.Primary`。`@Primary` 保证既有按
`RegisteredClientRepository` 注入的位置（授权服务、consent 接口）仍然拿到装饰器。

修改 `auth-server/src/main/java/com/example/auth/config/ConfiguredRegisteredClients.java` 的过滤逻辑：

```java
    private RegisteredClient withConfiguredLifetimes(RegisteredClient registeredClient) {
        if (registeredClient == null || !ClientManagementSettings.isEnabled(registeredClient)) {
            return null;
        }
        AuthServerProperties.Oauth configured = this.properties.oauth();
```

需要 import `com.example.auth.clientadmin.ClientManagementSettings`。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest='ClientAdminRepositoriesTest,AuthorizationServerMetadataTest,AuthServerMySqlIntegrationTest' test`

Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/clientadmin \
        auth-server/src/main/java/com/example/auth/config/AuthorizationServerConfig.java \
        auth-server/src/main/java/com/example/auth/config/ConfiguredRegisteredClients.java \
        auth-server/src/test/java/com/example/auth/clientadmin/ClientAdminRepositoriesTest.java
git commit -m "feat: add client management repositories"
```

---

### Task 4: 客户端创建与读取服务

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementException.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/CreateClientCommand.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementService.java`
- Create: `auth-server/src/test/java/com/example/auth/clientadmin/ClientManagementServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的仓储与视图、`PasswordEncoder` bean、`AuthServerProperties.oauth()`。
- Produces（Task 5/6 依赖）：
  - `ClientManagementException(HttpStatus status, String error, List<Detail> details)`，`Detail(String field, String code)`。
  - `CreateClientCommand(String clientId, String clientName, String type, List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> scopes, Boolean requireAuthorizationConsent)`。
  - `ClientManagementService.list(String)`、`get(String)`、`create(CreateClientCommand, AuthenticatedUser)` 返回 `CreatedClient(ClientDetailView client, String clientSecret)`。
  - 错误码：`client_id_taken`、`client_not_found`、`invalid_client_metadata`（details 带字段与原因码）。

- [ ] **Step 1: 写失败的测试**

创建 `auth-server/src/test/java/com/example/auth/clientadmin/ClientManagementServiceTest.java`：

```java
package com.example.auth.clientadmin;

import com.example.auth.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 创建/读取服务的校验、哈希与审计行为。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ClientManagementServiceTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", "Alice");

    @Autowired
    private ClientManagementService clientManagementService;

    @Autowired
    private JdbcRegisteredClientRepository rawRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createStoresAnEncodedSecretAndReturnsThePlaintextOnce() {
        ClientManagementService.CreatedClient created = this.clientManagementService.create(
                new CreateClientCommand("reporting-service", "Reporting Service", "machine",
                        List.of(), List.of(), List.of("weather:read"), null), ALICE);

        assertThat(created.clientSecret()).isNotBlank();
        assertThat(created.client().type()).isEqualTo("machine");
        assertThat(created.client().enabled()).isTrue();

        RegisteredClient stored = this.rawRepository.findByClientId("reporting-service");
        assertThat(stored).isNotNull();
        assertThat(stored.getClientSecret()).startsWith("{bcrypt}$2a$").doesNotContain(created.clientSecret());
        assertThat(auditCount("reporting-service", "CREATE")).isEqualTo(1);
    }

    @Test
    void createRejectsDuplicateClientIds() {
        this.clientManagementService.create(machineCommand("duplicate-client"), ALICE);

        assertThatThrownBy(() -> this.clientManagementService.create(machineCommand("duplicate-client"), ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class,
                        (ex) -> assertThat(ex.error()).isEqualTo("client_id_taken"));
    }

    @Test
    void createRejectsNonHttpsRedirectUris() {
        CreateClientCommand command = new CreateClientCommand("bad-uri-client", "Bad", "web",
                List.of("http://example.com/callback"), List.of(), List.of("openid"), null);

        assertThatThrownBy(() -> this.clientManagementService.create(command, ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class, (ex) -> {
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                    assertThat(ex.details())
                            .containsExactly(new ClientManagementException.Detail("redirectUris", "invalid_uri"));
                });
    }

    @Test
    void publicClientsHaveNoSecretAndRequirePkce() {
        ClientManagementService.CreatedClient created = this.clientManagementService.create(
                new CreateClientCommand("spa-client", "SPA", "public",
                        List.of("http://localhost:5173/callback"), List.of(), List.of("openid"), null), ALICE);

        assertThat(created.clientSecret()).isNull();
        assertThat(created.client().requireProofKey()).isTrue();
        assertThat(created.client().type()).isEqualTo("public");
    }

    @Test
    void getAndListReturnStoredClients() {
        this.clientManagementService.create(machineCommand("listable-client"), ALICE);

        assertThat(this.clientManagementService.get("listable-client").clientId()).isEqualTo("listable-client");
        assertThat(this.clientManagementService.list("listable")).hasSize(1);

        assertThatThrownBy(() -> this.clientManagementService.get("missing-client"))
                .isInstanceOfSatisfying(ClientManagementException.class,
                        (ex) -> assertThat(ex.error()).isEqualTo("client_not_found"));
    }

    private static CreateClientCommand machineCommand(String clientId) {
        return new CreateClientCommand(clientId, clientId, "machine", List.of(), List.of(),
                List.of("weather:read"), null);
    }

    private int auditCount(String clientId, String action) {
        Integer count = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_registered_client_audit WHERE client_id = ? AND action = ?
                """, Integer.class, clientId, action);
        return count == null ? 0 : count;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=ClientManagementServiceTest test`

Expected: 编译失败（`ClientManagementException`、`CreateClientCommand`、`ClientManagementService` 不存在）。

- [ ] **Step 3: 新增异常、命令与创建/读取服务**

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementException.java`：

```java
package com.example.auth.clientadmin;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 管理面业务错误：error 是稳定错误码，details 是字段级提示（可为空）。
 */
public class ClientManagementException extends RuntimeException {

    private final HttpStatus status;

    private final String error;

    private final List<Detail> details;

    public ClientManagementException(HttpStatus status, String error, List<Detail> details) {
        super(error);
        this.status = status;
        this.error = error;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() {
        return this.status;
    }

    public String error() {
        return this.error;
    }

    public List<Detail> details() {
        return this.details;
    }

    public record Detail(String field, String code) {
    }
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/CreateClientCommand.java`：

```java
package com.example.auth.clientadmin;

import java.util.List;

public record CreateClientCommand(String clientId, String clientName, String type, List<String> redirectUris,
                                  List<String> postLogoutRedirectUris, List<String> scopes,
                                  Boolean requireAuthorizationConsent) {
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementService.java`：

```java
package com.example.auth.clientadmin;

import com.example.auth.config.AuthServerProperties;
import com.example.auth.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 客户端管理的唯一写入口：校验、secret 生成与哈希、仓储写入与审计。
 */
@Service
public class ClientManagementService {

    static final Pattern CLIENT_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,63}$");

    static final Pattern SCOPE_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,64}$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JdbcRegisteredClientRepository registeredClientRepository;

    private final ClientQueryRepository clientQueryRepository;

    private final ClientAuditRepository clientAuditRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthServerProperties properties;

    public ClientManagementService(JdbcRegisteredClientRepository registeredClientRepository,
                                   ClientQueryRepository clientQueryRepository,
                                   ClientAuditRepository clientAuditRepository, PasswordEncoder passwordEncoder,
                                   AuthServerProperties properties) {
        this.registeredClientRepository = registeredClientRepository;
        this.clientQueryRepository = clientQueryRepository;
        this.clientAuditRepository = clientAuditRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    public List<ClientSummaryView> list(String query) {
        return this.clientQueryRepository.findAll(query);
    }

    public ClientDetailView get(String clientId) {
        return toDetail(requireClient(clientId));
    }

    @Transactional
    public CreatedClient create(CreateClientCommand command, AuthenticatedUser actor) {
        validateClientId(command.clientId());
        if (this.registeredClientRepository.findByClientId(command.clientId()) != null) {
            throw new ClientManagementException(HttpStatus.CONFLICT, "client_id_taken", List.of());
        }
        String type = normalizeType(command.type());
        List<String> redirectUris = validateUris(command.redirectUris(), "redirectUris");
        List<String> postLogoutRedirectUris = validateUris(command.postLogoutRedirectUris(),
                "postLogoutRedirectUris");
        List<String> scopes = validateScopes(command.scopes());
        if (!"machine".equals(type) && redirectUris.isEmpty()) {
            throw invalid("redirectUris", "required");
        }
        boolean requireConsent = command.requireAuthorizationConsent() == null
                ? !"machine".equals(type) : command.requireAuthorizationConsent();

        RegisteredClient.Builder builder = clientBuilder(command.clientId(), command.clientName(), type,
                redirectUris, postLogoutRedirectUris, scopes, requireConsent);
        String clientSecret = null;
        if (!"public".equals(type)) {
            clientSecret = generateSecret();
            builder.clientSecret(this.passwordEncoder.encode(clientSecret));
        }
        RegisteredClient saved = builder.build();
        this.registeredClientRepository.save(saved);
        this.clientAuditRepository.record(saved.getId(), saved.getClientId(), AuditAction.CREATE, actor, List.of());
        return new CreatedClient(toDetail(saved), clientSecret);
    }

    private RegisteredClient.Builder clientBuilder(String clientId, String clientName, String type,
                                                  List<String> redirectUris, List<String> postLogoutRedirectUris,
                                                  List<String> scopes, boolean requireConsent) {
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientIdIssuedAt(Instant.now())
                .clientName(clientName)
                .redirectUris((uris) -> uris.addAll(redirectUris))
                .postLogoutRedirectUris((uris) -> uris.addAll(postLogoutRedirectUris))
                .scopes((values) -> values.addAll(scopes))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(!"machine".equals(type))
                        .requireAuthorizationConsent(requireConsent)
                        .build())
                .tokenSettings(tokenSettings());
        switch (type) {
            case "web" -> builder
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            case "machine" -> builder
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
            case "public" -> builder
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
            default -> throw invalid("type", "unsupported");
        }
        return builder;
    }

    private TokenSettings tokenSettings() {
        return TokenSettings.builder()
                .authorizationCodeTimeToLive(this.properties.oauth().authorizationCodeTtl())
                .accessTokenTimeToLive(this.properties.oauth().accessTokenTtl())
                .refreshTokenTimeToLive(this.properties.oauth().refreshTokenTtl())
                .reuseRefreshTokens(false)
                .build();
    }

    private RegisteredClient requireClient(String clientId) {
        RegisteredClient client = this.registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw new ClientManagementException(HttpStatus.NOT_FOUND, "client_not_found", List.of());
        }
        return client;
    }

    private static ClientDetailView toDetail(RegisteredClient client) {
        TokenSettings tokenSettings = client.getTokenSettings();
        return new ClientDetailView(
                client.getClientId(),
                client.getClientName(),
                ClientTypeResolver.resolve(client),
                values(client.getClientAuthenticationMethods(), ClientAuthenticationMethod::getValue),
                values(client.getAuthorizationGrantTypes(), AuthorizationGrantType::getValue),
                List.copyOf(client.getRedirectUris()),
                List.copyOf(client.getPostLogoutRedirectUris()),
                List.copyOf(client.getScopes()),
                client.getClientSettings().isRequireProofKey(),
                client.getClientSettings().isRequireAuthorizationConsent(),
                client.getClientIdIssuedAt(),
                client.getClientSecretExpiresAt(),
                ClientManagementSettings.isEnabled(client),
                new ClientDetailView.TokenSettingsView(
                        tokenSettings.getAccessTokenTimeToLive().toString(),
                        tokenSettings.getRefreshTokenTimeToLive().toString(),
                        tokenSettings.getAuthorizationCodeTimeToLive().toString(),
                        tokenSettings.getIdTokenSignatureAlgorithm().getName(),
                        tokenSettings.getAccessTokenFormat().getValue()));
    }

    private static <T> List<String> values(Collection<T> source, Function<T, String> mapper) {
        return source.stream().map(mapper).toList();
    }

    private static void validateClientId(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw invalid("clientId", "invalid_format");
        }
    }

    private static String normalizeType(String type) {
        String value = type == null ? "" : type.trim();
        if (!List.of("web", "machine", "public").contains(value)) {
            throw invalid("type", "unsupported");
        }
        return value;
    }

    private static List<String> validateUris(List<String> uris, String field) {
        if (uris == null) {
            return List.of();
        }
        List<String> validated = new ArrayList<>();
        for (String value : uris) {
            if (value == null || value.isBlank()) {
                continue;
            }
            validated.add(validateUri(value.trim(), field));
        }
        return List.copyOf(validated);
    }

    private static String validateUri(String value, String field) {
        URI uri;
        try {
            uri = new URI(value);
        }
        catch (URISyntaxException ex) {
            throw invalid(field, "invalid_uri");
        }
        if (!uri.isAbsolute() || uri.getFragment() != null) {
            throw invalid(field, "invalid_uri");
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return value;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopback(uri.getHost())) {
            return value;
        }
        throw invalid(field, "invalid_uri");
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    private static List<String> validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw invalid("scopes", "required");
        }
        List<String> validated = new ArrayList<>();
        for (String scope : scopes) {
            if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
                throw invalid("scopes", "invalid_scope");
            }
            if (!validated.contains(scope)) {
                validated.add(scope);
            }
        }
        return List.copyOf(validated);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return BASE64_ENCODER.encodeToString(bytes);
    }

    private static ClientManagementException invalid(String field, String code) {
        return new ClientManagementException(HttpStatus.BAD_REQUEST, "invalid_client_metadata",
                List.of(new ClientManagementException.Detail(field, code)));
    }

    public record CreatedClient(ClientDetailView client, String clientSecret) {
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=ClientManagementServiceTest test`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/clientadmin \
        auth-server/src/test/java/com/example/auth/clientadmin/ClientManagementServiceTest.java
git commit -m "feat: add client management service"
```

---

### Task 5: 客户端生命周期服务（编辑 / 轮换 / 启停 / 删除）

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/UpdateClientCommand.java`
- Modify: `auth-server/src/main/java/com/example/auth/clientadmin/ClientManagementService.java`
- Modify: `auth-server/src/test/java/com/example/auth/clientadmin/ClientManagementServiceTest.java`

**Interfaces:**
- Consumes: Task 4 的 `ClientManagementService`、Task 3 的 `ClientQueryRepository.deleteAuthorizationsAndConsents` / `deleteRegisteredClient`。
- Produces（Task 6 依赖）：
  - `UpdateClientCommand(String clientName, List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> scopes, List<String> grantTypes, List<String> clientAuthenticationMethods, Boolean requireAuthorizationConsent)`。
  - `ClientManagementService.update(String, UpdateClientCommand, AuthenticatedUser)` → `ClientDetailView`。
  - `ClientManagementService.rotateSecret(String, AuthenticatedUser)` → 新明文 secret。
  - `ClientManagementService.setEnabled(String, boolean, AuthenticatedUser)` → `ClientDetailView`。
  - `ClientManagementService.delete(String, AuthenticatedUser)`。

- [ ] **Step 1: 写失败的测试**

在 `ClientManagementServiceTest` 中追加：

```java
    @Test
    void updateChangesFieldsAndPreservesTheSecret() {
        this.clientManagementService.create(machineCommand("updatable-client"), ALICE);
        String secretHash = this.rawRepository.findByClientId("updatable-client").getClientSecret();

        ClientDetailView updated = this.clientManagementService.update("updatable-client",
                new UpdateClientCommand("Renamed Client", null, null, List.of("weather:read", "profile"),
                        null, null, null), ALICE);

        assertThat(updated.clientName()).isEqualTo("Renamed Client");
        assertThat(updated.scopes()).containsExactly("weather:read", "profile");
        assertThat(this.rawRepository.findByClientId("updatable-client").getClientSecret()).isEqualTo(secretHash);
        assertThat(auditCount("updatable-client", "UPDATE")).isEqualTo(1);
    }

    @Test
    void updateRejectsPublicClientsWithClientCredentials() {
        assertThatThrownBy(() -> this.clientManagementService.update("auth-machine",
                new UpdateClientCommand(null, null, null, null, List.of("client_credentials"),
                        List.of("none"), null), ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class, (ex) -> {
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                    assertThat(ex.details()).containsExactly(new ClientManagementException.Detail(
                            "clientAuthenticationMethods", "public_client_requires_authorization_code"));
                });
    }

    @Test
    void rotateSecretReplacesTheStoredHash() {
        this.clientManagementService.create(machineCommand("rotating-client"), ALICE);
        String before = this.rawRepository.findByClientId("rotating-client").getClientSecret();

        String newSecret = this.clientManagementService.rotateSecret("rotating-client", ALICE);
        String after = this.rawRepository.findByClientId("rotating-client").getClientSecret();

        assertThat(after).isNotEqualTo(before).startsWith("{bcrypt}$2a$").doesNotContain(newSecret);
        assertThat(auditCount("rotating-client", "ROTATE_SECRET")).isEqualTo(1);
    }

    @Test
    void disablingCleansUpAuthorizationsAndConsentsAndStaysReversible() {
        this.clientManagementService.create(machineCommand("disabling-client"), ALICE);
        RegisteredClient stored = this.rawRepository.findByClientId("disabling-client");
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                VALUES (?, ?, ?)
                """, stored.getId(), "1", "openid");

        ClientDetailView disabled = this.clientManagementService.setEnabled("disabling-client", false, ALICE);

        assertThat(disabled.enabled()).isFalse();
        assertThat(this.clientManagementService.get("disabling-client").enabled()).isFalse();
        assertThat(this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = ?",
                Integer.class, stored.getId())).isZero();
        assertThat(auditCount("disabling-client", "DISABLE")).isEqualTo(1);

        ClientDetailView enabled = this.clientManagementService.setEnabled("disabling-client", true, ALICE);
        assertThat(enabled.enabled()).isTrue();
    }

    @Test
    void deleteRemovesTheClientButKeepsTheAudit() {
        this.clientManagementService.create(machineCommand("deletable-client"), ALICE);

        this.clientManagementService.delete("deletable-client", ALICE);

        assertThat(this.rawRepository.findByClientId("deletable-client")).isNull();
        assertThat(auditCount("deletable-client", "CREATE")).isEqualTo(1);
        assertThat(auditCount("deletable-client", "DELETE")).isEqualTo(1);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=ClientManagementServiceTest test`

Expected: 编译失败（`UpdateClientCommand`、`update`、`rotateSecret`、`setEnabled`、`delete` 不存在）。

- [ ] **Step 3: 实现生命周期方法**

创建 `auth-server/src/main/java/com/example/auth/clientadmin/UpdateClientCommand.java`：

```java
package com.example.auth.clientadmin;

import java.util.List;

public record UpdateClientCommand(String clientName, List<String> redirectUris, List<String> postLogoutRedirectUris,
                                  List<String> scopes, List<String> grantTypes,
                                  List<String> clientAuthenticationMethods, Boolean requireAuthorizationConsent) {
}
```

在 `ClientManagementService` 追加以下方法（放在 `create` 之后）：

```java
    @Transactional
    public ClientDetailView update(String clientId, UpdateClientCommand command, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        List<String> grantTypes = command.grantTypes() == null
                ? values(existing.getAuthorizationGrantTypes(), AuthorizationGrantType::getValue)
                : normalizeGrantTypes(command.grantTypes());
        List<String> authenticationMethods = command.clientAuthenticationMethods() == null
                ? values(existing.getClientAuthenticationMethods(), ClientAuthenticationMethod::getValue)
                : normalizeAuthenticationMethods(command.clientAuthenticationMethods());
        validateCombination(grantTypes, authenticationMethods);
        List<String> redirectUris = validateUris(command.redirectUris() == null
                ? List.copyOf(existing.getRedirectUris()) : command.redirectUris(), "redirectUris");
        List<String> postLogoutRedirectUris = validateUris(command.postLogoutRedirectUris() == null
                ? List.copyOf(existing.getPostLogoutRedirectUris()) : command.postLogoutRedirectUris(),
                "postLogoutRedirectUris");
        List<String> scopes = command.scopes() == null
                ? List.copyOf(existing.getScopes()) : validateScopes(command.scopes());
        if (grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue()) && redirectUris.isEmpty()) {
            throw invalid("redirectUris", "required");
        }
        boolean requireProofKey = authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue())
                || existing.getClientSettings().isRequireProofKey();
        boolean requireConsent = command.requireAuthorizationConsent() == null
                ? existing.getClientSettings().isRequireAuthorizationConsent()
                : command.requireAuthorizationConsent();

        ClientSettings.Builder settings = ClientSettings.withSettings(existing.getClientSettings().getSettings());
        settings.requireProofKey(requireProofKey);
        settings.requireAuthorizationConsent(requireConsent);

        RegisteredClient.Builder builder = RegisteredClient.from(existing)
                .clientName(command.clientName() == null ? existing.getClientName() : command.clientName())
                .redirectUris((uris) -> {
                    uris.clear();
                    uris.addAll(redirectUris);
                })
                .postLogoutRedirectUris((uris) -> {
                    uris.clear();
                    uris.addAll(postLogoutRedirectUris);
                })
                .scopes((values) -> {
                    values.clear();
                    values.addAll(scopes);
                })
                .authorizationGrantTypes((values) -> {
                    values.clear();
                    grantTypes.forEach((value) -> values.add(new AuthorizationGrantType(value)));
                })
                .clientAuthenticationMethods((values) -> {
                    values.clear();
                    authenticationMethods.forEach((value) -> values.add(new ClientAuthenticationMethod(value)));
                })
                .clientSettings(settings.build());
        if (authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue())) {
            builder.clientSecret(null);
        }
        RegisteredClient updated = builder.build();
        this.registeredClientRepository.save(updated);
        this.clientAuditRepository.record(existing.getId(), existing.getClientId(), AuditAction.UPDATE, actor,
                changedFields(command));
        return toDetail(updated);
    }

    @Transactional
    public String rotateSecret(String clientId, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        if (existing.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw invalid("clientAuthenticationMethods", "public_client_has_no_secret");
        }
        String clientSecret = generateSecret();
        RegisteredClient updated = RegisteredClient.from(existing)
                .clientSecret(this.passwordEncoder.encode(clientSecret))
                .build();
        this.registeredClientRepository.save(updated);
        this.clientAuditRepository.record(existing.getId(), clientId, AuditAction.ROTATE_SECRET, actor, List.of());
        return clientSecret;
    }

    @Transactional
    public ClientDetailView setEnabled(String clientId, boolean enabled, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        RegisteredClient updated = ClientManagementSettings.withEnabled(existing, enabled);
        this.registeredClientRepository.save(updated);
        if (!enabled) {
            this.clientQueryRepository.deleteAuthorizationsAndConsents(existing.getId());
        }
        this.clientAuditRepository.record(existing.getId(), clientId,
                enabled ? AuditAction.ENABLE : AuditAction.DISABLE, actor, List.of());
        return toDetail(updated);
    }

    @Transactional
    public void delete(String clientId, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        this.clientQueryRepository.deleteAuthorizationsAndConsents(existing.getId());
        this.clientQueryRepository.deleteRegisteredClient(existing.getId());
        this.clientAuditRepository.record(existing.getId(), clientId, AuditAction.DELETE, actor, List.of());
    }

    private static List<String> normalizeGrantTypes(List<String> grantTypes) {
        List<String> allowed = List.of("authorization_code", "client_credentials", "refresh_token");
        List<String> normalized = new ArrayList<>();
        for (String value : grantTypes) {
            if (value == null || !allowed.contains(value)) {
                throw invalid("grantTypes", "unsupported");
            }
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw invalid("grantTypes", "required");
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeAuthenticationMethods(List<String> authenticationMethods) {
        List<String> allowed = List.of("client_secret_basic", "client_secret_post", "none");
        List<String> normalized = new ArrayList<>();
        for (String value : authenticationMethods) {
            if (value == null || !allowed.contains(value)) {
                throw invalid("clientAuthenticationMethods", "unsupported");
            }
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw invalid("clientAuthenticationMethods", "required");
        }
        return List.copyOf(normalized);
    }

    private static void validateCombination(List<String> grantTypes, List<String> authenticationMethods) {
        if (authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue())) {
            boolean onlyAuthorizationCode = grantTypes.size() == 1
                    && grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
            if (!onlyAuthorizationCode) {
                throw invalid("clientAuthenticationMethods", "public_client_requires_authorization_code");
            }
        }
    }

    private static List<String> changedFields(UpdateClientCommand command) {
        List<String> fields = new ArrayList<>();
        if (command.clientName() != null) {
            fields.add("clientName");
        }
        if (command.redirectUris() != null) {
            fields.add("redirectUris");
        }
        if (command.postLogoutRedirectUris() != null) {
            fields.add("postLogoutRedirectUris");
        }
        if (command.scopes() != null) {
            fields.add("scopes");
        }
        if (command.grantTypes() != null) {
            fields.add("grantTypes");
        }
        if (command.clientAuthenticationMethods() != null) {
            fields.add("clientAuthenticationMethods");
        }
        if (command.requireAuthorizationConsent() != null) {
            fields.add("requireAuthorizationConsent");
        }
        return fields;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=ClientManagementServiceTest test`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/clientadmin \
        auth-server/src/test/java/com/example/auth/clientadmin/ClientManagementServiceTest.java
git commit -m "feat: complete client lifecycle management"
```

---

### Task 6: 管理 API 控制器与错误映射

**Files:**
- Modify: `auth-server/src/main/java/com/example/auth/web/ApiError.java`
- Create: `auth-server/src/main/java/com/example/auth/clientadmin/ClientAdminController.java`
- Test: `auth-server/src/test/java/com/example/auth/clientadmin/ClientAdminApiTest.java`

**Interfaces:**
- Consumes: Task 4/5 的 `ClientManagementService`、Task 1 的 `ROLE_PLATFORM_ADMIN`。
- Produces（前端依赖的 HTTP 契约）：
  - `GET /api/admin/clients?query=` → `200 [ClientSummaryView]`
  - `GET /api/admin/clients/{clientId}` → `200 ClientDetailView`
  - `POST /api/admin/clients` → `201 {"client":ClientDetailView,"clientSecret":string|null}`
  - `PUT /api/admin/clients/{clientId}` → `200 ClientDetailView`
  - `POST /api/admin/clients/{clientId}/secret` → `200 {"clientSecret":string}`
  - `POST /api/admin/clients/{clientId}/enable|disable` → `200 ClientDetailView`
  - `DELETE /api/admin/clients/{clientId}` → `204`
  - 错误体 `{"error":code,"details":[{"field","code"}]}`。
  - `ApiError(String error, List<Detail> details)` 并保留 `ApiError(String error)` 便捷构造。

- [ ] **Step 1: 写失败的测试**

先把 `PlatformAdminAccessTest#platformAdminsPassTheSecurityFilter` 的断言从 `status().isNotFound()` 改为
`status().isOk()`（控制器在本任务出现），并删除"控制器尚未实现"的注释。

创建 `auth-server/src/test/java/com/example/auth/clientadmin/ClientAdminApiTest.java`：

```java
package com.example.auth.clientadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理 API 端到端：权限、CRUD、secret 只返回一次、启停与删除对协议端点的影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ClientAdminApiTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void nonAdminsCannotCallTheApi() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"bob\",\"password\":\"bob-password\"}"))
                .andExpect(status().isOk());

        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    @Test
    void createReturnsTheSecretOnceAndStoresOnlyTheHash() throws Exception {
        MockHttpSession session = adminSession();

        MvcResult created = this.mockMvc.perform(post("/api/admin/clients").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"api-machine","clientName":"API Machine","type":"machine",
                                 "scopes":["weather:read"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client.clientId").value("api-machine"))
                .andExpect(jsonPath("$.clientSecret").isNotEmpty())
                .andReturn();
        String secret = JSON.readTree(created.getResponse().getContentAsString()).path("clientSecret").asText();

        String listBody = this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(secret);

        String stored = this.jdbcTemplate.queryForObject(
                "SELECT client_secret FROM oauth2_registered_client WHERE client_id = ?",
                String.class, "api-machine");
        assertThat(stored).startsWith("{bcrypt}$2a$").doesNotContain(secret);
    }

    @Test
    void invalidMetadataReturnsFieldLevelDetails() throws Exception {
        this.mockMvc.perform(post("/api/admin/clients").session(adminSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"Bad_ID","clientName":"Bad","type":"web","scopes":["openid"],
                                 "redirectUris":["https://example.com/callback"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
                .andExpect(jsonPath("$.details[0].field").value("clientId"));
    }

    @Test
    void createdMachineClientFetchesTokensAndRotationInvalidatesTheOldSecret() throws Exception {
        MockHttpSession session = adminSession();
        String secret = createMachineClient(session, "rotating-api-client");

        tokenRequest("rotating-api-client", secret).andExpect(status().isOk());

        MvcResult rotated = this.mockMvc.perform(post("/api/admin/clients/rotating-api-client/secret")
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String newSecret = JSON.readTree(rotated.getResponse().getContentAsString()).path("clientSecret").asText();
        assertThat(newSecret).isNotEqualTo(secret);

        tokenRequest("rotating-api-client", secret).andExpect(status().isUnauthorized());
        tokenRequest("rotating-api-client", newSecret).andExpect(status().isOk());
    }

    @Test
    void disablingStopsTokenIssuanceAndDeleteKeepsTheAudit() throws Exception {
        MockHttpSession session = adminSession();
        String secret = createMachineClient(session, "disabled-api-client");

        this.mockMvc.perform(post("/api/admin/clients/disabled-api-client/disable").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        tokenRequest("disabled-api-client", secret).andExpect(status().isUnauthorized());

        this.mockMvc.perform(delete("/api/admin/clients/disabled-api-client").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        Integer remaining = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = ?",
                Integer.class, "disabled-api-client");
        assertThat(remaining).isZero();
        Integer audits = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_registered_client_audit WHERE client_id = ?
                """, Integer.class, "disabled-api-client");
        assertThat(audits).isEqualTo(3);
    }

    @Test
    void unknownClientReturns404() throws Exception {
        this.mockMvc.perform(get("/api/admin/clients/missing-client").session(adminSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("client_not_found"));
    }

    private MockHttpSession adminSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private String createMachineClient(MockHttpSession session, String clientId) throws Exception {
        MvcResult created = this.mockMvc.perform(post("/api/admin/clients").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"" + clientId + "\",\"clientName\":\"" + clientId
                                + "\",\"type\":\"machine\",\"scopes\":[\"weather:read\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(created.getResponse().getContentAsString()).path("clientSecret").asText();
    }

    private ResultActions tokenRequest(String clientId, String secret) throws Exception {
        return this.mockMvc.perform(post("/oauth2/token")
                .with(httpBasic(clientId, secret))
                .param("grant_type", "client_credentials")
                .param("scope", "weather:read"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest=ClientAdminApiTest test`

Expected: 编译失败（`ClientAdminController` 不存在）。

- [ ] **Step 3: 扩展 ApiError 并新增控制器**

把 `auth-server/src/main/java/com/example/auth/web/ApiError.java` 改为：

```java
package com.example.auth.web;

import java.util.List;

/**
 * 稳定的 API 错误体：{"error":"...","details":[...]}。前端按 error 码做文案映射。
 */
public record ApiError(String error, List<Detail> details) {

    public static final String INVALID_CREDENTIALS = "invalid_credentials";

    public static final String ACCESS_DENIED = "access_denied";

    public ApiError(String error) {
        this(error, null);
    }

    public record Detail(String field, String code) {
    }
}
```

创建 `auth-server/src/main/java/com/example/auth/clientadmin/ClientAdminController.java`：

```java
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl auth-server -DskipFrontend=true -Dtest='ClientAdminApiTest,PlatformAdminAccessTest' test`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add auth-server/src/main/java/com/example/auth/web/ApiError.java \
        auth-server/src/main/java/com/example/auth/clientadmin/ClientAdminController.java \
        auth-server/src/test/java/com/example/auth/clientadmin/ClientAdminApiTest.java
git commit -m "feat: expose client management api"
```

---

### Task 7: 前端 API 客户端、会话类型、路由与 SPA 外壳

**Files:**
- Create: `auth-web/src/api/adminClients.ts`
- Modify: `auth-web/src/api/auth.ts`
- Modify: `auth-web/src/router.ts`
- Modify: `auth-web/tests/router-guard.test.ts`
- Modify: `auth-web/tests/home-view.test.ts`
- Modify: `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java`
- Modify: `auth-server/src/main/java/com/example/auth/web/SpaForwardController.java`
- Modify: `auth-server/src/test/java/com/example/auth/web/SpaRoutingTest.java`

**Interfaces:**
- Consumes: Task 6 的 HTTP 契约；Task 1 的 `platformAdmin` 会话字段。
- Produces（Task 8-10 依赖）：
  - `SessionState.platformAdmin: boolean`。
  - `listClients(query?)`、`getClient(clientId)`、`createClient(payload)`、`updateClient(clientId, payload)`、`rotateClientSecret(clientId)`、`setClientEnabled(clientId, enabled)`、`deleteClient(clientId)` 以及 `ClientSummary` / `ClientDetail` / `CreateClientPayload` / `UpdateClientPayload` 类型。
  - 路由 `/admin/clients`、`/admin/clients/new`、`/admin/clients/:clientId`，守卫规则：未登录去 `/login`；平台管理员放行 `/admin/**`；非管理员访问 `/admin/**` 回 `/`。
  - SPA 外壳支持 `/admin/clients` 系列路径直达。

- [ ] **Step 1: 写失败的测试**

把 `auth-web/tests/router-guard.test.ts` 的 `session()` 工厂改为带 `platformAdmin`，并追加两个用例：

```ts
function session(overrides: Partial<SessionState> = {}): SessionState {
  return {
    authenticated: true,
    user: { account: 'alice', name: 'Alice' },
    organization: { id: 10, name: 'Alpha' },
    pending: null,
    platformAdmin: false,
    ...overrides,
  }
}
```

```ts
  it('lets platform admins reach the admin routes without an organization', async () => {
    const guard = createSessionGuard(async () => session({ platformAdmin: true, organization: null }))

    expect(await guard(route('/admin/clients'))).toBe(true)
  })

  it('sends non-admins away from the admin routes', async () => {
    const guard = createSessionGuard(async () => session())

    expect(await guard(route('/admin/clients'))).toEqual({ path: '/' })
  })
```

把 `auth-web/tests/home-view.test.ts` 两处 `getSession` mock 返回值补上 `platformAdmin: false`。

把 `auth-server/src/test/java/com/example/auth/web/SpaRoutingTest.java` 的路径列表改为：

```java
        for (String path : List.of("/", "/login", "/organizations", "/consent",
                "/admin/clients", "/admin/clients/new", "/admin/clients/auth-machine")) {
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix auth-web test -- tests/router-guard.test.ts` 与
`mvn -pl auth-server -DskipFrontend=true -Dtest=SpaRoutingTest test`

Expected: 前端 TypeScript 测试因 `platformAdmin` 未定义与新用例失败；后端 `/admin/clients` 返回 302
而不是转发 `index.html`。

- [ ] **Step 3: 增加会话字段与 API 客户端**

修改 `auth-web/src/api/auth.ts` 的 `SessionState`：

```ts
export interface SessionState {
  authenticated: boolean
  user: UserView | null
  organization: OrganizationView | null
  pending: string | null
  platformAdmin: boolean
}
```

创建 `auth-web/src/api/adminClients.ts`：

```ts
import { api } from './client'

export type ClientType = 'web' | 'machine' | 'public' | 'custom'

export interface ClientSummary {
  clientId: string
  clientName: string
  type: ClientType
  grantTypes: string[]
  scopes: string[]
  enabled: boolean
  updatedAt: string | null
  updatedBy: string | null
}

export interface ClientTokenSettings {
  accessTokenTimeToLive: string
  refreshTokenTimeToLive: string
  authorizationCodeTimeToLive: string
  idTokenSignatureAlgorithm: string
  accessTokenFormat: string
}

export interface ClientDetail {
  clientId: string
  clientName: string
  type: ClientType
  clientAuthenticationMethods: string[]
  grantTypes: string[]
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  requireProofKey: boolean
  requireAuthorizationConsent: boolean
  clientIdIssuedAt: string | null
  clientSecretExpiresAt: string | null
  enabled: boolean
  tokenSettings: ClientTokenSettings
}

export interface CreateClientPayload {
  clientId: string
  clientName: string
  type: 'web' | 'machine' | 'public'
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  requireAuthorizationConsent: boolean
}

export interface UpdateClientPayload {
  clientName: string
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  grantTypes: string[]
  clientAuthenticationMethods: string[]
  requireAuthorizationConsent: boolean
}

export interface CreatedClient {
  client: ClientDetail
  clientSecret: string | null
}

export function listClients(query = ''): Promise<ClientSummary[]> {
  const suffix = query ? `?query=${encodeURIComponent(query)}` : ''
  return api<ClientSummary[]>(`/api/admin/clients${suffix}`)
}

export function getClient(clientId: string): Promise<ClientDetail> {
  return api<ClientDetail>(`/api/admin/clients/${encodeURIComponent(clientId)}`)
}

export function createClient(payload: CreateClientPayload): Promise<CreatedClient> {
  return api<CreatedClient>('/api/admin/clients', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateClient(clientId: string, payload: UpdateClientPayload): Promise<ClientDetail> {
  return api<ClientDetail>(`/api/admin/clients/${encodeURIComponent(clientId)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function rotateClientSecret(clientId: string): Promise<{ clientSecret: string }> {
  return api<{ clientSecret: string }>(`/api/admin/clients/${encodeURIComponent(clientId)}/secret`, {
    method: 'POST',
  })
}

export function setClientEnabled(clientId: string, enabled: boolean): Promise<ClientDetail> {
  const action = enabled ? 'enable' : 'disable'
  return api<ClientDetail>(`/api/admin/clients/${encodeURIComponent(clientId)}/${action}`, { method: 'POST' })
}

export function deleteClient(clientId: string): Promise<void> {
  return api<void>(`/api/admin/clients/${encodeURIComponent(clientId)}`, { method: 'DELETE' })
}
```

- [ ] **Step 4: 更新路由与守卫**

把 `auth-web/src/router.ts` 改为：

```ts
import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { getSession, type SessionState } from './api/auth'
import { navigate } from './navigation'

export type GuardResult = boolean | { path: string }

/**
 * 会话守卫：未登录去 /login；平台管理员放行 /admin/**；其余用户先绑定组织。
 */
export function createSessionGuard(load: () => Promise<SessionState>) {
  return async (to: Pick<RouteLocationNormalized, 'path'>): Promise<GuardResult> => {
    const session = await load()
    if (!session.authenticated) {
      return to.path === '/login' ? true : { path: '/login' }
    }
    if (to.path.startsWith('/admin')) {
      return session.platformAdmin ? true : { path: '/' }
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
    { path: '/admin/clients', component: () => import('./views/admin/ClientListView.vue') },
    { path: '/admin/clients/new', component: () => import('./views/admin/ClientCreateView.vue') },
    { path: '/admin/clients/:clientId', component: () => import('./views/admin/ClientDetailView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(createSessionGuard(getSession))
```

同时创建三个临时外壳组件，保证路由解析可用（Task 8-10 会整体替换它们）：

`auth-web/src/views/admin/ClientListView.vue`、`ClientCreateView.vue`、`ClientDetailView.vue`：

```vue
<script setup lang="ts">
</script>

<template>
  <div />
</template>
```

- [ ] **Step 5: 让 SPA 外壳支持管理路由**

在 `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java` 的 permitAll 列表里加入
`"/admin/**"`（静态外壳放行，数据接口仍由 `/api/admin/**` 规则保护）：

```java
                .requestMatchers("/", "/login", "/organizations", "/consent", "/admin/**", "/assets/**",
                        "/favicon.ico", "/index.html",
                        "/api/auth/session", "/api/auth/login", "/error", "/actuator/health").permitAll()
```

把 `auth-server/src/main/java/com/example/auth/web/SpaForwardController.java` 的映射改为：

```java
    @GetMapping({"/", "/login", "/organizations", "/consent", "/admin/clients", "/admin/clients/new",
            "/admin/clients/{clientId}"})
    String spa(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        return "forward:/index.html";
    }
```

- [ ] **Step 6: 运行测试确认通过**

Run: `npm --prefix auth-web test` 与
`mvn -pl auth-server -DskipFrontend=true -Dtest='SpaRoutingTest,PlatformAdminAccessTest' test`

Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add auth-web/src/api/adminClients.ts auth-web/src/api/auth.ts auth-web/src/router.ts \
        auth-web/src/views/admin/ClientListView.vue auth-web/src/views/admin/ClientCreateView.vue \
        auth-web/src/views/admin/ClientDetailView.vue \
        auth-web/tests/router-guard.test.ts auth-web/tests/home-view.test.ts \
        auth-server/src/main/java/com/example/auth/config/SecurityConfig.java \
        auth-server/src/main/java/com/example/auth/web/SpaForwardController.java \
        auth-server/src/test/java/com/example/auth/web/SpaRoutingTest.java
git commit -m "feat: add client admin api client and routes"
```

---

### Task 8: 客户端列表页

**Files:**
- Create: `auth-web/src/views/admin/ClientListView.vue`
- Create: `auth-web/tests/admin-clients-list.test.ts`
- Modify: `auth-web/src/i18n/zh.ts`
- Modify: `auth-web/src/i18n/en.ts`
- Modify: `auth-web/src/views/HomeView.vue`
- Modify: `auth-web/tests/home-view.test.ts`

**Interfaces:**
- Consumes: Task 7 的 `listClients` 与 `SessionState.platformAdmin`。
- Produces: 首页入口（仅平台管理员可见）与 `clientAdmin.*` i18n 命名空间的前半部分。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/admin-clients-list.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientListView from '../src/views/admin/ClientListView.vue'
import { i18n, setLocale } from '../src/i18n'
import { listClients } from '../src/api/adminClients'

vi.mock('../src/api/adminClients', () => ({ listClients: vi.fn() }))

const routerLinkStub = { template: '<a><slot /></a>' }

describe('ClientListView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(listClients).mockReset()
  })

  it('renders clients with type, scopes and audit metadata', async () => {
    vi.mocked(listClients).mockResolvedValue([
      {
        clientId: 'auth-machine',
        clientName: 'Auth Machine',
        type: 'machine',
        grantTypes: ['client_credentials'],
        scopes: ['weather:read'],
        enabled: true,
        updatedAt: '2026-09-20T02:00:00Z',
        updatedBy: 'alice',
      },
    ])
    const wrapper = mount(ClientListView, {
      global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('auth-machine')
    expect(wrapper.text()).toContain('weather:read')
    expect(wrapper.text()).toContain('alice')
  })

  it('searches with the typed query', async () => {
    vi.mocked(listClients).mockResolvedValue([])
    const wrapper = mount(ClientListView, {
      global: { plugins: [i18n], stubs: { RouterLink: routerLinkStub } },
    })
    await flushPromises()

    await wrapper.get('[data-field="query"]').setValue('machine')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(listClients).toHaveBeenLastCalledWith('machine')
  })
})
```

在 `auth-web/tests/home-view.test.ts` 追加用例：

```ts
  it('shows the client management entry only for platform admins', async () => {
    vi.mocked(getSession).mockResolvedValue({
      authenticated: true,
      user: { account: 'alice', name: 'Alice' },
      organization: { id: 10, name: 'Alpha' },
      pending: null,
      platformAdmin: true,
    })
    const adminWrapper = mount(HomeView, {
      global: { plugins: [i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(adminWrapper.text()).toContain('客户端管理')

    vi.mocked(getSession).mockResolvedValue({
      authenticated: true,
      user: { account: 'alice', name: 'Alice' },
      organization: { id: 10, name: 'Alpha' },
      pending: null,
      platformAdmin: false,
    })
    const userWrapper = mount(HomeView, { global: { plugins: [i18n] } })
    await flushPromises()
    expect(userWrapper.text()).not.toContain('客户端管理')
  })
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix auth-web test -- tests/admin-clients-list.test.ts`

Expected: FAIL —— 组件不存在。

- [ ] **Step 3: 新增列表页与首页入口**

创建 `auth-web/src/views/admin/ClientListView.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AuthLayout from '../../components/AuthLayout.vue'
import { ApiError } from '../../api/client'
import { listClients, type ClientSummary } from '../../api/adminClients'

const { t } = useI18n()
const clients = ref<ClientSummary[]>([])
const query = ref('')
const error = ref<string | null>(null)

async function load(): Promise<void> {
  error.value = null
  try {
    clients.value = await listClients(query.value.trim())
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 403 ? 'denied' : 'failed'
  }
}

onMounted(load)
</script>

<template>
  <AuthLayout :title="t('clientAdmin.list.title')" :subtitle="t('clientAdmin.list.subtitle')">
    <div class="mt-6 flex flex-wrap items-center gap-2">
      <form class="flex flex-1 gap-2" @submit.prevent="load">
        <input v-model="query" data-field="query" type="search" :placeholder="t('clientAdmin.list.search')"
               class="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
        <button type="submit"
                class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50">
          {{ t('clientAdmin.list.searchAction') }}
        </button>
      </form>
      <RouterLink to="/admin/clients/new"
                  class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500">
        {{ t('clientAdmin.list.create') }}
      </RouterLink>
    </div>

    <p v-if="error" class="mt-4 text-sm text-rose-600">{{ t(`clientAdmin.errors.${error}`) }}</p>

    <table v-else class="mt-6 w-full border-collapse text-left text-sm">
      <thead>
        <tr class="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
          <th class="py-2">{{ t('clientAdmin.list.columns.clientId') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.name') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.type') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.grantTypes') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.scopes') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.status') }}</th>
          <th class="py-2">{{ t('clientAdmin.list.columns.updatedBy') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="client in clients" :key="client.clientId" class="border-b border-slate-100">
          <td class="py-2">
            <RouterLink :to="`/admin/clients/${client.clientId}`" class="font-semibold text-indigo-600">
              {{ client.clientId }}
            </RouterLink>
          </td>
          <td class="py-2">{{ client.clientName }}</td>
          <td class="py-2">{{ t(`clientAdmin.type.${client.type}`) }}</td>
          <td class="py-2">{{ client.grantTypes.join(', ') }}</td>
          <td class="py-2">{{ client.scopes.join(', ') }}</td>
          <td class="py-2">{{ client.enabled ? t('clientAdmin.enabled') : t('clientAdmin.disabled') }}</td>
          <td class="py-2">{{ client.updatedBy ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
  </AuthLayout>
</template>
```

在 `auth-web/src/views/HomeView.vue` 的登出按钮之前加入管理入口：

```vue
      <RouterLink v-if="session?.platformAdmin" to="/admin/clients"
                  class="rounded-lg bg-indigo-600 px-4 py-2 text-center text-sm font-semibold text-white transition hover:bg-indigo-500">
        {{ t('clientAdmin.list.entry') }}
      </RouterLink>
```

- [ ] **Step 4: 补齐 i18n 文案**

在 `auth-web/src/i18n/zh.ts` 的 `home` 之后加入：

```ts
  clientAdmin: {
    enabled: '已启用',
    disabled: '已停用',
    type: { web: 'Web 应用', machine: '机器客户端', public: '公共客户端', custom: '自定义' },
    errors: { denied: '需要平台管理员权限。', failed: '加载失败，请稍后重试。' },
    list: {
      title: '客户端管理',
      subtitle: '管理授权服务器的 OAuth 客户端',
      search: '按 client_id 或名称搜索',
      searchAction: '搜索',
      create: '新建客户端',
      entry: '客户端管理',
      columns: {
        clientId: 'Client ID', name: '名称', type: '类型', grantTypes: '授权类型',
        scopes: 'Scopes', status: '状态', updatedBy: '最近操作人',
      },
    },
  },
```

在 `auth-web/src/i18n/en.ts` 的 `home` 之后加入：

```ts
  clientAdmin: {
    enabled: 'Enabled',
    disabled: 'Disabled',
    type: { web: 'Web app', machine: 'Machine client', public: 'Public client', custom: 'Custom' },
    errors: { denied: 'Platform administrator permission required.', failed: 'Could not load. Please retry.' },
    list: {
      title: 'Client management',
      subtitle: 'Manage the OAuth clients of this authorization server',
      search: 'Search by client_id or name',
      searchAction: 'Search',
      create: 'New client',
      entry: 'Clients',
      columns: {
        clientId: 'Client ID', name: 'Name', type: 'Type', grantTypes: 'Grant types',
        scopes: 'Scopes', status: 'Status', updatedBy: 'Last updated by',
      },
    },
  },
```

- [ ] **Step 5: 运行测试确认通过**

Run: `npm --prefix auth-web test -- tests/admin-clients-list.test.ts tests/home-view.test.ts`

Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add auth-web/src/views/admin/ClientListView.vue auth-web/tests/admin-clients-list.test.ts \
        auth-web/src/i18n/zh.ts auth-web/src/i18n/en.ts \
        auth-web/src/views/HomeView.vue auth-web/tests/home-view.test.ts
git commit -m "feat: add client list view"
```

---

### Task 9: 创建页与一次性 secret 展示

**Files:**
- Modify（整体替换 Task 7 的空壳）: `auth-web/src/views/admin/ClientCreateView.vue`
- Create: `auth-web/tests/admin-clients-create.test.ts`
- Modify: `auth-web/src/i18n/zh.ts`
- Modify: `auth-web/src/i18n/en.ts`

**Interfaces:**
- Consumes: Task 7 的 `createClient`、`CreateClientPayload`。
- Produces: 创建表单（类型预设、URI 多行输入、scope 解析）与"secret 只显示一次"确认流程。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/admin-clients-create.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientCreateView from '../src/views/admin/ClientCreateView.vue'
import { i18n, setLocale } from '../src/i18n'
import { createClient, type CreatedClient } from '../src/api/adminClients'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))
vi.mock('../src/api/adminClients', () => ({ createClient: vi.fn() }))

const created: CreatedClient = {
  client: {
    clientId: 'api-machine',
    clientName: 'API Machine',
    type: 'machine',
    clientAuthenticationMethods: ['client_secret_basic'],
    grantTypes: ['client_credentials'],
    redirectUris: [],
    postLogoutRedirectUris: [],
    scopes: ['weather:read'],
    requireProofKey: false,
    requireAuthorizationConsent: false,
    clientIdIssuedAt: '2026-09-20T02:00:00Z',
    clientSecretExpiresAt: null,
    enabled: true,
    tokenSettings: {
      accessTokenTimeToLive: 'PT10M',
      refreshTokenTimeToLive: 'PT720H',
      authorizationCodeTimeToLive: 'PT5M',
      idTokenSignatureAlgorithm: 'RS256',
      accessTokenFormat: 'self-contained',
    },
  },
  clientSecret: 'top-secret',
}

describe('ClientCreateView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(createClient).mockReset()
    routerMock.push.mockReset()
  })

  it('creates a machine client and navigates only after the secret is confirmed', async () => {
    vi.mocked(createClient).mockResolvedValue(created)
    const wrapper = mount(ClientCreateView, { global: { plugins: [i18n] } })

    await wrapper.get('[data-field="clientId"]').setValue('api-machine')
    await wrapper.get('[data-field="clientName"]').setValue('API Machine')
    await wrapper.get('[data-field="type"]').setValue('machine')
    await wrapper.get('[data-field="scopes"]').setValue('weather:read')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(createClient).toHaveBeenCalledWith({
      clientId: 'api-machine',
      clientName: 'API Machine',
      type: 'machine',
      redirectUris: [],
      postLogoutRedirectUris: [],
      scopes: ['weather:read'],
      requireAuthorizationConsent: false,
    })
    expect(wrapper.text()).toContain('top-secret')
    expect(routerMock.push).not.toHaveBeenCalled()

    await wrapper.get('[data-action="finish"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith('/admin/clients/api-machine')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix auth-web test -- tests/admin-clients-create.test.ts`

Expected: FAIL —— 空壳组件没有表单与 secret 对话框。

- [ ] **Step 3: 实现创建页**

把 `auth-web/src/views/admin/ClientCreateView.vue` 替换为：

```vue
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/AuthLayout.vue'
import { ApiError } from '../../api/client'
import { createClient, type CreateClientPayload } from '../../api/adminClients'

const { t } = useI18n()
const router = useRouter()

const form = reactive({
  clientId: '',
  clientName: '',
  type: 'web' as CreateClientPayload['type'],
  redirectUris: '',
  postLogoutRedirectUris: '',
  scopes: '',
})

const error = ref<string | null>(null)
const submitting = ref(false)
const created = ref<{ clientId: string; clientSecret: string | null } | null>(null)

function splitLines(value: string): string[] {
  return value.split('\n').map((line) => line.trim()).filter((line) => line.length > 0)
}

function splitScopes(value: string): string[] {
  return value.split(/[\s,]+/).map((scope) => scope.trim()).filter((scope) => scope.length > 0)
}

async function submit(): Promise<void> {
  error.value = null
  submitting.value = true
  try {
    const payload: CreateClientPayload = {
      clientId: form.clientId.trim(),
      clientName: form.clientName.trim(),
      type: form.type,
      redirectUris: splitLines(form.redirectUris),
      postLogoutRedirectUris: splitLines(form.postLogoutRedirectUris),
      scopes: splitScopes(form.scopes),
      requireAuthorizationConsent: form.type !== 'machine',
    }
    const result = await createClient(payload)
    created.value = { clientId: result.client.clientId, clientSecret: result.clientSecret }
  } catch (e) {
    error.value = e instanceof ApiError && e.code === 'client_id_taken' ? 'clientIdTaken' : 'invalid'
  } finally {
    submitting.value = false
  }
}

async function copySecret(): Promise<void> {
  if (created.value?.clientSecret) {
    await navigator.clipboard?.writeText(created.value.clientSecret)
  }
}

function finish(): void {
  if (created.value) {
    void router.push(`/admin/clients/${encodeURIComponent(created.value.clientId)}`)
  }
}
</script>

<template>
  <AuthLayout :title="t('clientAdmin.create.title')" :subtitle="t('clientAdmin.create.subtitle')">
    <form class="mt-6 grid gap-4" @submit.prevent="submit">
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.clientId') }}</span>
        <input v-model="form.clientId" data-field="clientId" required
               class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.clientName') }}</span>
        <input v-model="form.clientName" data-field="clientName" required
               class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.type') }}</span>
        <select v-model="form.type" data-field="type" class="rounded-lg border border-slate-300 px-3 py-2">
          <option value="web">{{ t('clientAdmin.type.web') }}</option>
          <option value="machine">{{ t('clientAdmin.type.machine') }}</option>
          <option value="public">{{ t('clientAdmin.type.public') }}</option>
        </select>
      </label>
      <label v-if="form.type !== 'machine'" class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.redirectUris') }}</span>
        <textarea v-model="form.redirectUris" data-field="redirectUris" rows="2"
                  class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label v-if="form.type !== 'machine'" class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.postLogoutRedirectUris') }}</span>
        <textarea v-model="form.postLogoutRedirectUris" data-field="postLogoutRedirectUris" rows="2"
                  class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <label class="grid gap-1 text-sm">
        <span class="font-semibold text-slate-700">{{ t('clientAdmin.create.scopes') }}</span>
        <input v-model="form.scopes" data-field="scopes" required
               class="rounded-lg border border-slate-300 px-3 py-2" />
      </label>

      <p v-if="error" class="text-sm text-rose-600">{{ t(`clientAdmin.errors.${error}`) }}</p>

      <button type="submit" :disabled="submitting"
              class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
        {{ submitting ? t('clientAdmin.create.submitting') : t('clientAdmin.create.submit') }}
      </button>
    </form>

    <div v-if="created" class="mt-6 rounded-lg border border-amber-300 bg-amber-50 p-4">
      <p class="text-sm font-semibold text-amber-900">{{ t('clientAdmin.create.secretTitle') }}</p>
      <p class="mt-2 break-all font-mono text-sm text-amber-900">{{ created.clientSecret }}</p>
      <div class="mt-3 flex gap-2">
        <button type="button" data-action="copy" class="rounded-lg border border-amber-400 px-3 py-1 text-sm"
                @click="copySecret">{{ t('clientAdmin.create.copy') }}</button>
        <button type="button" data-action="finish" class="rounded-lg bg-amber-600 px-3 py-1 text-sm text-white"
                @click="finish">{{ t('clientAdmin.create.saved') }}</button>
      </div>
    </div>
  </AuthLayout>
</template>
```

- [ ] **Step 4: 补齐 i18n 文案**

在 `auth-web/src/i18n/zh.ts` 的 `clientAdmin` 里补充 `errors` 与 `create`：

```ts
    errors: {
      denied: '需要平台管理员权限。',
      failed: '加载失败，请稍后重试。',
      invalid: '提交内容不合法，请检查表单。',
      clientIdTaken: '该 client_id 已存在。',
      notFound: '客户端不存在或已被删除。',
    },
    create: {
      title: '新建客户端',
      subtitle: '创建后 client_id 不可修改；secret 只会显示一次',
      clientId: 'Client ID',
      clientName: '名称',
      type: '类型',
      redirectUris: 'Redirect URIs（每行一个）',
      postLogoutRedirectUris: 'Post-logout redirect URIs（每行一个）',
      scopes: 'Scopes（空格或逗号分隔）',
      submit: '创建',
      submitting: '创建中…',
      secretTitle: '请立即保存 client secret，它只会显示这一次',
      copy: '复制',
      saved: '我已保存',
    },
```

在 `auth-web/src/i18n/en.ts` 的 `clientAdmin` 里补充：

```ts
    errors: {
      denied: 'Platform administrator permission required.',
      failed: 'Could not load. Please retry.',
      invalid: 'The submitted values are not valid. Check the form.',
      clientIdTaken: 'This client_id already exists.',
      notFound: 'The client does not exist or was deleted.',
    },
    create: {
      title: 'New client',
      subtitle: 'client_id is immutable after creation; the secret is shown only once',
      clientId: 'Client ID',
      clientName: 'Name',
      type: 'Type',
      redirectUris: 'Redirect URIs (one per line)',
      postLogoutRedirectUris: 'Post-logout redirect URIs (one per line)',
      scopes: 'Scopes (space or comma separated)',
      submit: 'Create',
      submitting: 'Creating…',
      secretTitle: 'Save the client secret now — it will not be shown again',
      copy: 'Copy',
      saved: 'I saved it',
    },
```

- [ ] **Step 5: 运行测试确认通过**

Run: `npm --prefix auth-web test -- tests/admin-clients-create.test.ts`

Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add auth-web/src/views/admin/ClientCreateView.vue auth-web/tests/admin-clients-create.test.ts \
        auth-web/src/i18n/zh.ts auth-web/src/i18n/en.ts
git commit -m "feat: add client creation view"
```

---

### Task 10: 详情/编辑页与危险操作

**Files:**
- Modify（整体替换 Task 7 的空壳）: `auth-web/src/views/admin/ClientDetailView.vue`
- Create: `auth-web/tests/admin-clients-detail.test.ts`
- Modify: `auth-web/src/i18n/zh.ts`
- Modify: `auth-web/src/i18n/en.ts`

**Interfaces:**
- Consumes: Task 7 的 `getClient`、`updateClient`、`rotateClientSecret`、`setClientEnabled`、`deleteClient`。
- Produces: 编辑表单、轮换 secret 的一次性展示、启停与"输入 client_id 才能删除"的确认流程。

- [ ] **Step 1: 写失败的测试**

创建 `auth-web/tests/admin-clients-detail.test.ts`：

```ts
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClientDetailView from '../src/views/admin/ClientDetailView.vue'
import { i18n, setLocale } from '../src/i18n'
import {
  deleteClient,
  getClient,
  rotateClientSecret,
  setClientEnabled,
  updateClient,
  type ClientDetail,
} from '../src/api/adminClients'

const routerMock = vi.hoisted(() => ({ push: vi.fn(), clientId: 'auth-machine' }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { clientId: routerMock.clientId } }),
  useRouter: () => ({ push: routerMock.push }),
}))
vi.mock('../src/api/adminClients', () => ({
  getClient: vi.fn(),
  updateClient: vi.fn(),
  rotateClientSecret: vi.fn(),
  setClientEnabled: vi.fn(),
  deleteClient: vi.fn(),
}))

const detail: ClientDetail = {
  clientId: 'auth-machine',
  clientName: 'Auth Machine',
  type: 'machine',
  clientAuthenticationMethods: ['client_secret_basic'],
  grantTypes: ['client_credentials'],
  redirectUris: [],
  postLogoutRedirectUris: [],
  scopes: ['weather:read'],
  requireProofKey: false,
  requireAuthorizationConsent: false,
  clientIdIssuedAt: '2026-09-18T02:00:00Z',
  clientSecretExpiresAt: null,
  enabled: true,
  tokenSettings: {
    accessTokenTimeToLive: 'PT10M',
    refreshTokenTimeToLive: 'PT1H',
    authorizationCodeTimeToLive: 'PT5M',
    idTokenSignatureAlgorithm: 'RS256',
    accessTokenFormat: 'self-contained',
  },
}

describe('ClientDetailView', () => {
  beforeEach(() => {
    setLocale('zh')
    vi.mocked(getClient).mockReset()
    vi.mocked(getClient).mockResolvedValue(detail)
    vi.mocked(updateClient).mockReset()
    vi.mocked(updateClient).mockResolvedValue(detail)
    vi.mocked(rotateClientSecret).mockReset()
    vi.mocked(setClientEnabled).mockReset()
    vi.mocked(deleteClient).mockReset()
    routerMock.push.mockReset()
  })

  it('loads the client and saves edited fields', async () => {
    const wrapper = mount(ClientDetailView, { global: { plugins: [i18n] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Auth Machine')
    await wrapper.get('[data-field="clientName"]').setValue('Renamed Machine')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(updateClient).toHaveBeenCalledWith('auth-machine',
      expect.objectContaining({ clientName: 'Renamed Machine', scopes: ['weather:read'] }))
  })

  it('rotates the secret and shows the new value', async () => {
    vi.mocked(rotateClientSecret).mockResolvedValue({ clientSecret: 'rotated-secret' })
    const wrapper = mount(ClientDetailView, { global: { plugins: [i18n] } })
    await flushPromises()

    await wrapper.get('[data-action="rotate"]').trigger('click')
    await flushPromises()

    expect(rotateClientSecret).toHaveBeenCalledWith('auth-machine')
    expect(wrapper.text()).toContain('rotated-secret')
  })

  it('requires the typed client id before deleting', async () => {
    vi.mocked(deleteClient).mockResolvedValue()
    const wrapper = mount(ClientDetailView, { global: { plugins: [i18n] } })
    await flushPromises()

    await wrapper.get('[data-action="delete"]').trigger('click')
    expect(deleteClient).not.toHaveBeenCalled()

    await wrapper.get('[data-field="deleteConfirm"]').setValue('auth-machine')
    await wrapper.get('[data-action="delete"]').trigger('click')
    await flushPromises()

    expect(deleteClient).toHaveBeenCalledWith('auth-machine')
    expect(routerMock.push).toHaveBeenCalledWith('/admin/clients')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm --prefix auth-web test -- tests/admin-clients-detail.test.ts`

Expected: FAIL —— 空壳组件没有表单与危险操作区。

- [ ] **Step 3: 实现详情页**

把 `auth-web/src/views/admin/ClientDetailView.vue` 替换为：

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../../components/AuthLayout.vue'
import { ApiError } from '../../api/client'
import {
  deleteClient,
  getClient,
  rotateClientSecret,
  setClientEnabled,
  updateClient,
  type ClientDetail,
  type UpdateClientPayload,
} from '../../api/adminClients'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const clientId = String(route.params.clientId)

const client = ref<ClientDetail | null>(null)
const error = ref<string | null>(null)
const newSecret = ref<string | null>(null)
const deleteConfirm = ref('')
const saving = ref(false)

const form = reactive({
  clientName: '',
  redirectUris: '',
  postLogoutRedirectUris: '',
  scopes: '',
  grantTypes: [] as string[],
  clientAuthenticationMethods: [] as string[],
  requireAuthorizationConsent: true,
})

function splitLines(value: string): string[] {
  return value.split('\n').map((line) => line.trim()).filter((line) => line.length > 0)
}

function splitScopes(value: string): string[] {
  return value.split(/[\s,]+/).map((scope) => scope.trim()).filter((scope) => scope.length > 0)
}

async function load(): Promise<void> {
  error.value = null
  try {
    const detail = await getClient(clientId)
    client.value = detail
    form.clientName = detail.clientName
    form.redirectUris = detail.redirectUris.join('\n')
    form.postLogoutRedirectUris = detail.postLogoutRedirectUris.join('\n')
    form.scopes = detail.scopes.join(' ')
    form.grantTypes = [...detail.grantTypes]
    form.clientAuthenticationMethods = [...detail.clientAuthenticationMethods]
    form.requireAuthorizationConsent = detail.requireAuthorizationConsent
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 404 ? 'notFound' : 'failed'
  }
}

async function save(): Promise<void> {
  saving.value = true
  error.value = null
  try {
    const payload: UpdateClientPayload = {
      clientName: form.clientName.trim(),
      redirectUris: splitLines(form.redirectUris),
      postLogoutRedirectUris: splitLines(form.postLogoutRedirectUris),
      scopes: splitScopes(form.scopes),
      grantTypes: form.grantTypes,
      clientAuthenticationMethods: form.clientAuthenticationMethods,
      requireAuthorizationConsent: form.requireAuthorizationConsent,
    }
    client.value = await updateClient(clientId, payload)
  } catch (e) {
    error.value = 'invalid'
  } finally {
    saving.value = false
  }
}

async function rotate(): Promise<void> {
  newSecret.value = (await rotateClientSecret(clientId)).clientSecret
}

async function toggle(): Promise<void> {
  if (client.value) {
    client.value = await setClientEnabled(clientId, !client.value.enabled)
  }
}

async function remove(): Promise<void> {
  if (deleteConfirm.value !== clientId) {
    return
  }
  await deleteClient(clientId)
  void router.push('/admin/clients')
}

onMounted(load)
</script>

<template>
  <AuthLayout :title="t('clientAdmin.detail.title', { clientId })" :subtitle="t('clientAdmin.detail.subtitle')">
    <p v-if="error" class="mt-4 text-sm text-rose-600">{{ t(`clientAdmin.errors.${error}`) }}</p>

    <template v-if="client">
      <form class="mt-6 grid gap-4" @submit.prevent="save">
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.clientName') }}</span>
          <input v-model="form.clientName" data-field="clientName"
                 class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.redirectUris') }}</span>
          <textarea v-model="form.redirectUris" data-field="redirectUris" rows="2"
                    class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.postLogoutRedirectUris') }}</span>
          <textarea v-model="form.postLogoutRedirectUris" data-field="postLogoutRedirectUris" rows="2"
                    class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <label class="grid gap-1 text-sm">
          <span class="font-semibold text-slate-700">{{ t('clientAdmin.detail.scopes') }}</span>
          <input v-model="form.scopes" data-field="scopes"
                 class="rounded-lg border border-slate-300 px-3 py-2" />
        </label>
        <button type="submit" data-action="save" :disabled="saving"
                class="justify-self-start rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-60">
          {{ t('clientAdmin.detail.save') }}
        </button>
      </form>

      <section class="mt-8 rounded-lg border border-rose-200 p-4">
        <h2 class="text-sm font-semibold text-rose-700">{{ t('clientAdmin.detail.dangerTitle') }}</h2>
        <p class="mt-2 text-xs text-rose-600">{{ t('clientAdmin.detail.dangerHint') }}</p>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <button type="button" data-action="rotate"
                  class="rounded-lg border border-rose-300 px-3 py-1 text-sm text-rose-700"
                  @click="rotate">{{ t('clientAdmin.detail.rotate') }}</button>
          <button type="button" data-action="toggle"
                  class="rounded-lg border border-rose-300 px-3 py-1 text-sm text-rose-700"
                  @click="toggle">
            {{ client.enabled ? t('clientAdmin.detail.disable') : t('clientAdmin.detail.enable') }}
          </button>
        </div>

        <p v-if="newSecret" class="mt-3 break-all rounded-lg bg-rose-50 p-3 font-mono text-sm text-rose-800">
          {{ newSecret }}
        </p>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <input v-model="deleteConfirm" data-field="deleteConfirm"
                 :placeholder="t('clientAdmin.detail.deletePlaceholder')"
                 class="rounded-lg border border-rose-300 px-3 py-1 text-sm" />
          <button type="button" data-action="delete"
                  class="rounded-lg bg-rose-600 px-3 py-1 text-sm font-semibold text-white"
                  @click="remove">{{ t('clientAdmin.detail.delete') }}</button>
        </div>
      </section>
    </template>
  </AuthLayout>
</template>
```

- [ ] **Step 4: 补齐 i18n 文案**

在 `auth-web/src/i18n/zh.ts` 的 `clientAdmin` 里补充：

```ts
    detail: {
      title: '编辑 {clientId}',
      subtitle: 'client_id 不可修改；token 生命周期由部署配置控制',
      clientName: '名称',
      redirectUris: 'Redirect URIs（每行一个）',
      postLogoutRedirectUris: 'Post-logout redirect URIs（每行一个）',
      scopes: 'Scopes（空格或逗号分隔）',
      save: '保存',
      dangerTitle: '危险操作',
      dangerHint: '停用或删除会立即停止新令牌签发，但已签发的访问令牌在到期前仍然有效。',
      rotate: '轮换 secret',
      enable: '启用',
      disable: '停用',
      deletePlaceholder: '输入 client_id 以确认删除',
      delete: '删除',
    },
```

在 `auth-web/src/i18n/en.ts` 的 `clientAdmin` 里补充：

```ts
    detail: {
      title: 'Edit {clientId}',
      subtitle: 'client_id is immutable; token lifetimes are deployment-controlled',
      clientName: 'Name',
      redirectUris: 'Redirect URIs (one per line)',
      postLogoutRedirectUris: 'Post-logout redirect URIs (one per line)',
      scopes: 'Scopes (space or comma separated)',
      save: 'Save',
      dangerTitle: 'Danger zone',
      dangerHint: 'Disabling or deleting stops new tokens immediately, but already issued access tokens stay valid until they expire.',
      rotate: 'Rotate secret',
      enable: 'Enable',
      disable: 'Disable',
      deletePlaceholder: 'Type the client_id to confirm deletion',
      delete: 'Delete',
    },
```

- [ ] **Step 5: 运行测试确认通过**

Run: `npm --prefix auth-web test`

Expected: PASS（全部前端用例）

- [ ] **Step 6: 提交**

```bash
git add auth-web/src/views/admin/ClientDetailView.vue auth-web/tests/admin-clients-detail.test.ts \
        auth-web/src/i18n/zh.ts auth-web/src/i18n/en.ts
git commit -m "feat: add client detail view"
```

---

### Task 11: 文档与验收说明

**Files:**
- Modify: `auth-server/README.md`
- Modify: `docs/auth-server-testing.md`

**Interfaces:**
- Consumes: Task 1-10 完成后的实际端点与配置项。
- Produces: 运维可照着执行的客户端管理与验收说明。

- [ ] **Step 1: 更新 auth-server README**

在 `auth-server/README.md` 的端点表里补一行：

```markdown
| `/api/admin/clients` | 平台管理员客户端管理 API（列表/创建/编辑/轮换 secret/启停/删除） |
```

在阈值/配置章节补上管理员配置说明：

```markdown
### 客户端管理

平台管理员通过 auth-web 的 `/admin/clients` 管理 OAuth 客户端，写操作走 `/api/admin/clients`。
管理员账号由 `AUTH_ADMIN_ACCOUNTS` 指定（逗号分隔，例如 `AUTH_ADMIN_ACCOUNTS=alice`），只有这些
账号能访问管理 API；secret 只在创建或轮换时显示一次，库里存 `{bcrypt}` 哈希。

生产客户端不再手工 INSERT `oauth2_registered_client`（V2/V3 种子仅用于本地开发）。停用与删除
会清理该客户端的授权与同意记录；已签发的自包含 JWT 在到期前仍然有效。
```

- [ ] **Step 2: 更新测试文档**

在 `docs/auth-server-testing.md` 的验收步骤后追加：

```markdown
## 客户端管理验收

1. `AUTH_ADMIN_ACCOUNTS=alice mvn -pl auth-server spring-boot:run`，用 alice 登录 auth-web。
2. 首页进入"客户端管理" → 新建机器客户端（scopes 填 `weather:read`），保存弹窗里的 secret。
3. 用该 client 走 client_credentials 取得 token：
   `curl -u <client_id>:<secret> -d 'grant_type=client_credentials&scope=weather:read' http://localhost:8083/oauth2/token`
4. 在详情页轮换 secret：旧 secret 立即失败，新 secret 成功。
5. 停用客户端：token 端点返回 401；列表仍可见且可重新启用。
6. 删除客户端：注册行消失，`oauth2_registered_client_audit` 中保留 CREATE/DISABLE/DELETE 记录。
```

- [ ] **Step 3: 提交**

```bash
git add auth-server/README.md docs/auth-server-testing.md
git commit -m "docs: document client management"
```

---
