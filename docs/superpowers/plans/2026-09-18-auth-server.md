# Auth Server OAuth 2.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 Maven 多模块工程中新增独立 `auth-server`，基于现有 MySQL 用户/组织/角色表提供 OAuth 2.1 与 OIDC 服务。

**Architecture:** 使用 Spring Authorization Server 的标准端点和 JDBC Repository；认证通过只读 JDBC 查询 `sys_*` 表完成，授权请求在登录后绑定一个组织及其角色。JWT 使用外部 PKCS#12/JKS RSA 密钥签名，Flyway 只管理 auth-server 自有 OAuth 表，完全不修改 `weather-mcp-server`。

**Tech Stack:** Spring Boot 4.0.8、Spring Security Authorization Server、Spring Security OAuth2/OIDC、Spring JDBC、MySQL 8、Flyway、Thymeleaf、JUnit/MockMvc、MySQL 8 Testcontainers。

**Spec:** `docs/superpowers/specs/2026-09-18-auth-server-design.md`

## Global Constraints

- 现有 `sys_user`、`sys_org`、`sys_role`、`sys_user_org_role` 表只读。
- `sys_user_org_role` 按 `user_id + org_id` 隔离角色，令牌不得包含其他组织角色。
- Authorization Code 必须使用 PKCE `S256`；不实现 Password Grant、Device Code、Token Exchange。
- 生产签名密钥来自外部 PKCS#12/JKS，issuer 必须通过配置提供且不能降级为随机密钥。
- 客户端、授权和 consent 使用 Spring Authorization Server JDBC schema；Flyway 只迁移这些表。
- 不修改 `weather-mcp-server` 的代码、配置或测试。
- 每个任务完成后运行其列出的最小测试；可提交时使用小粒度 commit。

---

### Task 1: 新增 Maven 模块与运行配置

**Files:**
- Modify: `pom.xml`（加入 `auth-server` module）
- Create: `auth-server/pom.xml`
- Create: `auth-server/src/main/java/com/example/auth/AuthServerApplication.java`
- Create: `auth-server/src/main/resources/application.yml`
- Create: `auth-server/src/test/java/com/example/auth/AuthServerApplicationTest.java`

**Interfaces:**
- Consumes: 父工程的 Java 21、Spring Boot 4.0.8 dependency management。
- Produces: 可启动的 `auth-server`，默认端口 8083，配置前缀 `auth.*`、MySQL datasource 和 issuer。

- [x] **Step 1: 写模块启动测试**
  在 `AuthServerApplicationTest` 使用 `@SpringBootTest`，断言 `spring.application.name=auth-server` 和端口配置存在。
- [x] **Step 2: 运行测试确认缺少模块**
  Run: `mvn -pl auth-server -DskipTests=false test`
  Expected: FAIL，模块和启动类尚不存在。
- [x] **Step 3: 添加最小 Maven 模块和启动类**
  引入 web、security、oauth2-authorization-server、oauth2-resource-server、jdbc、thymeleaf、flyway、mysql-connector、test 依赖；主类使用 `@SpringBootApplication`。
- [x] **Step 4: 添加 local/default 配置**
  配置 `${AUTH_ISSUER:http://localhost:8083}`、`${DB_URL:jdbc:mysql://localhost:3306/auth}`、数据库用户名密码、密钥库路径/密码/类型/别名和令牌时长；不写生产 secret。
- [x] **Step 5: 运行模块测试**
  Run: `mvn -pl auth-server test`
  Expected: PASS（数据库和完整安全 Bean 在后续任务加入前使用 test profile 条件化）。
- [x] **Step 6: Commit**
  `git add pom.xml auth-server && git commit -m "feat: scaffold auth server module"`

### Task 2: Flyway OAuth schema 与只读身份 Repository

**Files:**
- Create: `auth-server/src/main/resources/db/migration/V1__oauth_authorization_schema.sql`
- Create: `auth-server/src/main/resources/db/migration/V2__seed_local_clients.sql`
- Create: `auth-server/src/main/java/com/example/auth/identity/UserAccount.java`
- Create: `auth-server/src/main/java/com/example/auth/identity/Organization.java`
- Create: `auth-server/src/main/java/com/example/auth/identity/Role.java`
- Create: `auth-server/src/main/java/com/example/auth/identity/IdentityRepository.java`
- Create: `auth-server/src/main/java/com/example/auth/identity/JdbcIdentityRepository.java`
- Create: `auth-server/src/test/java/com/example/auth/identity/JdbcIdentityRepositoryTest.java`

**Interfaces:**
- Consumes: MySQL `sys_user(id,account,name,password)`, `sys_org(id,name)`, `sys_role(id,name)`, `sys_user_org_role(id,user_id,org_id,role_id)`。
- Produces: `findByAccount(String): Optional<UserAccount>`、`findOrganizations(long): List<Organization>`、`findRoles(long,long): List<Role>`；查询只读且按组织过滤。

- [x] **Step 1: 写 Repository 测试**
  用 `@JdbcTest`/Testcontainers 准备四张 `sys_*` 表和两名用户，验证 BCrypt 密码字段原样读取、组织列表只返回用户关联组织、角色查询不会返回其他组织角色。
- [x] **Step 2: 运行测试确认失败**
  Run: `mvn -pl auth-server -Dtest=JdbcIdentityRepositoryTest test`
  Expected: FAIL，模型和 Repository 不存在。
- [x] **Step 3: 添加领域 record 与 JDBC 实现**
  使用参数化 SQL、`RowMapper` 和显式列名；禁止拼接账户、组织或角色输入。
- [x] **Step 4: 添加 Spring Authorization Server MySQL schema**
  从对应版本官方 schema 导入三张 OAuth 表，补充 MySQL 长文本、索引和主键约束；初始化 SQL 仅预置本地测试客户端，secret 使用编码值。
- [x] **Step 5: 运行身份与 Flyway 测试**
  Run: `mvn -pl auth-server -Dtest=JdbcIdentityRepositoryTest test`
  Expected: PASS。
- [x] **Step 6: Commit**
  `git add auth-server/src/main/resources/db auth-server/src/main/java/com/example/auth/identity auth-server/src/test/java/com/example/auth/identity && git commit -m "feat: add identity repository and oauth schema"`

### Task 3: Spring Authorization Server、JDBC Repository 与 JWK

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/config/AuthorizationServerConfig.java`
- Create: `auth-server/src/main/java/com/example/auth/config/KeyStoreConfig.java`
- Create: `auth-server/src/main/java/com/example/auth/token/TokenClaimsCustomizer.java`
- Create: `auth-server/src/test/java/com/example/auth/config/AuthorizationServerMetadataTest.java`
- Modify: `auth-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Flyway OAuth tables、外部 keystore 配置、`auth.issuer`。
- Produces: discovery、authorize、token、jwks、introspection、revocation、OIDC endpoints；`JWKSource<SecurityContext>` 和 `JwtDecoder` Bean；JWT claims customizer。

- [x] **Step 1: 写 discovery/JWKS 测试**
  MockMvc 断言 `/.well-known/oauth-authorization-server`、`/.well-known/openid-configuration` 和 `/oauth2/jwks` 返回 200 且 issuer 一致。
- [x] **Step 2: 运行测试确认失败**
  Run: `mvn -pl auth-server -Dtest=AuthorizationServerMetadataTest test`
  Expected: FAIL，安全端点尚未配置。
- [x] **Step 3: 配置外部 RSA JWK Source**
  从 PKCS#12/JKS 读取私钥和证书，生成 `RSAKey`；密钥文件、密码、类型和 alias 缺失或错误时抛出启动异常。test profile 使用固定测试 keystore。
- [x] **Step 4: 配置 JDBC RegisteredClient/Authorization/Consent Service**
  注入官方 JDBC 实现；设置 `AuthorizationServerSettings.issuer(auth.issuer)`、OAuth2 token generator、OIDC enabled、UserInfo endpoint 和标准 client authentication。
- [x] **Step 5: 添加 claims customizer 骨架**
  仅根据认证主体中已绑定的用户/组织上下文填充 `preferred_username`、`name`、`org_id`、`org_name`、`roles`；client credentials 分支不得填充用户 claims。
- [x] **Step 6: 运行元数据测试**
  Run: `mvn -pl auth-server -Dtest=AuthorizationServerMetadataTest test`
  Expected: PASS。
- [x] **Step 7: Commit**
  `git add auth-server/src/main/java/com/example/auth/config auth-server/src/main/java/com/example/auth/token auth-server/src/test/java/com/example/auth/config auth-server/src/main/resources/application.yml && git commit -m "feat: configure jdbc authorization server and jwks"`

### Task 4: 用户登录与组织绑定授权上下文

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/security/AuthenticatedUser.java`
- Create: `auth-server/src/main/java/com/example/auth/security/OrganizationAuthorization.java`
- Create: `auth-server/src/main/java/com/example/auth/security/IdentityAuthenticationProvider.java`
- Create: `auth-server/src/main/java/com/example/auth/web/LoginController.java`
- Create: `auth-server/src/main/java/com/example/auth/web/OrganizationController.java`
- Create: `auth-server/src/main/resources/templates/login.html`
- Create: `auth-server/src/main/resources/templates/organizations.html`
- Create: `auth-server/src/test/java/com/example/auth/security/IdentityAuthenticationProviderTest.java`
- Create: `auth-server/src/test/java/com/example/auth/web/OrganizationAuthorizationFlowTest.java`

**Interfaces:**
- Consumes: `IdentityRepository.findByAccount/findOrganizations/findRoles`、Spring `OAuth2AuthorizationConsent`/authorization request context。
- Produces: BCrypt `AuthenticationProvider`；服务端会话中的 `OrganizationAuthorization(userId, orgId, orgName, roles)`；拒绝跨组织选择。

- [x] **Step 1: 写认证 Provider 测试**
  验证正确 BCrypt 密码成功、错误密码和不存在账号返回统一认证失败、不从请求参数读取用户 ID。
- [x] **Step 2: 写组织流程测试**
  验证多组织展示、非法组织返回 `access_denied`、单组织可继续、角色仅来自选中组织。
- [x] **Step 3: 实现认证主体和 Provider**
  `AuthenticatedUser` 实现 Spring `UserDetails`，保存 numeric/string user ID、account、name；Provider 使用 `PasswordEncoder.matches`。
- [x] **Step 4: 实现登录和组织 Controller**
  登录成功后保存待完成 OAuth authorization request；组织 POST 只接受用户可用组织，成功后写入 `OrganizationAuthorization` 并回到授权链。
- [x] **Step 5: 添加 Thymeleaf 页面**
  表单启用 CSRF，错误信息不区分账号存在性；组织页面显示名称和隐藏的服务端请求状态，不信任客户端传入角色。
- [x] **Step 6: 运行安全流程测试**
  Run: `mvn -pl auth-server -Dtest=IdentityAuthenticationProviderTest,OrganizationAuthorizationFlowTest test`
  Expected: PASS。
- [x] **Step 7: Commit**
  `git add auth-server/src/main/java/com/example/auth/security auth-server/src/main/java/com/example/auth/web auth-server/src/main/resources/templates auth-server/src/test/java/com/example/auth/security auth-server/src/test/java/com/example/auth/web && git commit -m "feat: add bcrypt login and organization authorization"`

### Task 5: Client registration 与完整 OAuth/OIDC token claims

**Files:**
- Modify: `auth-server/src/main/resources/db/migration/V2__seed_local_clients.sql`
- Modify: `auth-server/src/main/java/com/example/auth/token/TokenClaimsCustomizer.java`
- Create: `auth-server/src/main/java/com/example/auth/oidc/UserInfoService.java`
- Create: `auth-server/src/test/java/com/example/auth/oauth/OAuthAuthorizationCodeFlowTest.java`
- Create: `auth-server/src/test/java/com/example/auth/oauth/ClientCredentialsFlowTest.java`
- Create: `auth-server/src/test/java/com/example/auth/oidc/UserInfoTest.java`

**Interfaces:**
- Consumes: 组织绑定认证主体、RegisteredClient JDBC repository、scope 配置。
- Produces: `auth-web-public`（authorization_code + refresh_token + PKCE）、`auth-machine`（client_credentials）；用户 access/id token claims 与 `/userinfo` 响应。

- [x] **Step 1: 写授权码 + PKCE 测试**
  使用 MockMvc 登录并完成组织选择、scope consent、code exchange；断言缺少或错误 verifier、redirect URI 不匹配、重复 code 均失败。
- [x] **Step 2: 写 client credentials 测试**
  使用 Basic client auth 获取 token；断言返回 scope，且不包含 `org_id`、`roles`、`preferred_username`。
- [x] **Step 3: 写 OIDC UserInfo 测试**
  断言 `openid` token 可访问 UserInfo，返回当前用户、组织和组织角色；无 token 或其他组织上下文返回 401/403。
- [x] **Step 4: 配置初始客户端**
  初始化 SQL 只写编码 secret、精确 redirect URI、授权类型、PKCE 要求和允许 scopes；生产客户端通过运维 SQL 添加。
- [x] **Step 5: 实现 claims 与 UserInfo**
  从 `OrganizationAuthorization` 生成用户 claims；OIDC UserInfo 重新读取用户和组织，避免从不可信请求参数组装响应。
- [x] **Step 6: 运行 OAuth/OIDC 测试**
  Run: `mvn -pl auth-server -Dtest=OAuthAuthorizationCodeFlowTest,ClientCredentialsFlowTest,UserInfoTest test`
  Expected: PASS。
- [x] **Step 7: Commit**
  `git add auth-server/src/main auth-server/src/test && git commit -m "feat: support oauth flows and oidc user info"`

### Task 6: 生命周期安全策略、时长配置与关系撤销

**Files:**
- Create: `auth-server/src/main/java/com/example/auth/config/SecurityConfig.java`
- Create: `auth-server/src/main/java/com/example/auth/token/AuthorizationTokenCustomizer.java`
- Create: `auth-server/src/test/java/com/example/auth/security/TokenLifecycleSecurityTest.java`
- Modify: `auth-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 授权服务 Bean、身份 Repository、配置的 token/session duration。
- Produces: 10 分钟 access token、10 分钟 ID token、30 天 rotating refresh token、5 分钟 code；introspection/revoke 仅机密客户端可用。

- [x] **Step 1: 写生命周期测试**
  验证过期 token、重复使用 refresh token、撤销后的 authorization、被移除组织关系的 refresh 均失败。
- [x] **Step 2: 配置 token settings**
  使用 `TokenSettings`、`AuthorizationServerSettings` 和 refresh reuse=false；时长全部从 `auth.oauth.*` 绑定。
- [x] **Step 3: 配置安全过滤链**
  标准 OAuth endpoints 按 Authorization Server matcher 处理；表单登录和自定义页面启用 CSRF；introspection/revoke 强制 client authentication；不添加资源服务器保护规则。
- [x] **Step 4: 实现刷新前组织关系复核**
  在刷新用户 token 的扩展点重新查询 `findOrganizations`/`findRoles`，组织不存在时返回 `invalid_grant`。
- [x] **Step 5: 运行生命周期测试**
  Run: `mvn -pl auth-server -Dtest=TokenLifecycleSecurityTest test`
  Expected: PASS。
- [x] **Step 6: Commit**
  `git add auth-server/src/main/java/com/example/auth/config auth-server/src/main/java/com/example/auth/token auth-server/src/test/java/com/example/auth/security auth-server/src/main/resources/application.yml && git commit -m "feat: enforce token lifecycle and security policy"`

### Task 7: 集成验证、文档与验收脚本

**Files:**
- Create: `auth-server/src/test/java/com/example/auth/AuthServerMySqlIntegrationTest.java`
- Create: `auth-server/src/test/resources/application-test.yml`
- Create: `auth-server/README.md`
- Create: `docs/auth-server-testing.md`
- Create: `scripts/auth-server-smoke.sh`
- Modify: `README.md`（仅新增 auth-server 模块说明，不修改 MCP OAuth 设计）

**Interfaces:**
- Consumes: 前六个任务的启动配置、Flyway schema 和标准端点。
- Produces: MySQL 8 集成验证、local keystore 配置示例、curl smoke test 和部署安全说明。

- [x] **Step 1: 添加 MySQL 8 集成测试**
  Testcontainers 启动 MySQL 8，创建只读 `sys_*` fixtures，运行 Flyway，验证三张 OAuth 表和端到端 client credentials/authorization code 数据持久化。
- [x] **Step 2: 添加 test profile**
  禁止连接现有环境数据库；所有测试使用随机容器或显式 H2 替代（若官方 schema 兼容性不足则统一使用 MySQL container）。
- [x] **Step 3: 编写 local keystore 和配置说明**
  说明 PKCS#12/JKS 创建、issuer、数据库和客户端 secret 注入方式；明确开发 secret 不得用于生产。
- [x] **Step 4: 编写 smoke script**
  脚本检查 discovery、JWKS、client credentials、introspection/revoke，并对错误凭证返回非零状态；不把 token 打印到持久日志。
- [x] **Step 5: 运行模块和全量验证**
  Run: `mvn -pl auth-server verify`；再运行 `mvn verify`。
  Expected: auth-server 测试和现有 `weather-service`、`weather-mcp-server` 测试全部 PASS。
- [x] **Step 6: Commit**
  `git add auth-server docs/auth-server-testing.md scripts/auth-server-smoke.sh README.md && git commit -m "docs: add auth server deployment and smoke tests"`

## Self-Review Checklist

- 现有身份表只读、组织隔离和 BCrypt：Task 2、Task 4、Task 6。
- OAuth 2.1 三种流程、PKCE、refresh rotation：Task 5、Task 6。
- OIDC discovery、ID Token、UserInfo、JWKS：Task 3、Task 5。
- MySQL 8、Flyway、JDBC authorization persistence：Task 2、Task 7。
- 外部 keystore、issuer、不可随机降级：Task 1、Task 3、Task 7。
- 自定义页面、错误和 CSRF：Task 4、Task 6。
- 不修改 `weather-mcp-server`：全局约束和 Task 7 验证。

