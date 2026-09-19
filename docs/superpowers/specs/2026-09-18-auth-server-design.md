# Auth Server OAuth 2.1 设计

> 日期：2026-09-18  
> 状态：待评审  
> 范围：新增独立 `auth-server`，不修改 `weather-mcp-server`

## 1. 目标与边界

新增一个基于 Spring Boot 的独立 OAuth 2.1 Authorization Server / OpenID Provider，为后续业务系统和 Higress gateway 提供统一认证与令牌服务。

本期包含：

- OAuth 2.1 Authorization Code + PKCE、Refresh Token、Client Credentials。
- OpenID Connect 1.0：Discovery、ID Token、UserInfo、JWKS。
- 基于现有 `sys_*` 表的 BCrypt 用户认证、组织选择和组织级角色声明。
- MySQL 8 持久化 OAuth 客户端、授权记录和用户 consent。
- Flyway 管理 auth-server 自有表和初始化 SQL；现有业务表只读。
- 自定义登录页、组织选择页、Scope 授权确认页。
- 外部 PKCS#12/JKS RSA 密钥库签名，issuer 和密钥配置外置。

本期不包含：

- `weather-mcp-server` 的改造或接入。
- 修改、迁移或维护现有 `sys_user`、`sys_org`、`sys_role`、`sys_user_org_role` 表。
- RBAC 管理后台、组织管理后台、动态客户端注册。
- Password Grant、Device Code、Token Exchange。
- 外部 IdP 集成；后续可替换用户认证适配层。

## 2. 项目结构与组件

在当前 Maven 多模块工程增加 `auth-server` 子模块。模块内部按职责拆分：

- `web`：登录、组织选择、授权确认页面与错误页面。
- `identity`：只读 JDBC 查询用户、组织、角色，并以 BCrypt 校验密码。
- `authorization`：Spring Authorization Server 配置、标准 OAuth/OIDC 端点和授权请求上下文。
- `persistence`：官方 JDBC Repository 对接 OAuth 表；Flyway 迁移脚本。
- `token`：JWK Source、JWT claims 定制、组织角色映射和 UserInfo 映射。
- `config`：issuer、数据库、密钥库、令牌时长及安全响应头配置。

Spring Authorization Server 负责协议正确性、客户端认证、授权码、令牌和 OIDC 端点；应用代码只实现现有身份源和组织选择所需的扩展。

## 3. 现有身份数据模型

以下表由其他系统维护，auth-server 只读：

```text
sys_user(id, account, name, password)
sys_org(id, name)
sys_role(id, name)
sys_user_org_role(id, user_id, org_id, role_id)
```

登录按 `account` 查询用户，使用 BCrypt PasswordEncoder 校验 `password`。认证主体保存用户 ID、账号和姓名。

用户可选组织由 `sys_user_org_role` 与 `sys_org` 联查得到。组织选择必须来自该用户实际关联的组织；角色只从所选 `org_id` 过滤后查询，不聚合其他组织角色。

## 4. auth-server 自有数据

Flyway 仅创建和升级以下 OAuth 数据表，表结构遵循 Spring Authorization Server JDBC schema，并根据 MySQL 8 类型和索引要求调整：

- `oauth2_registered_client`
- `oauth2_authorization`
- `oauth2_authorization_consent`

客户端通过初始化 SQL 或运维 SQL 预置，不提供动态注册 API。客户端 secret 只保存编码后的值，生产明文 secret 通过密钥管理或部署注入，不提交到仓库。

## 5. 端点与流程

标准端点由 Spring Authorization Server 提供：

- `GET /.well-known/oauth-authorization-server`
- `GET /.well-known/openid-configuration`
- `GET /oauth2/authorize`
- `POST /oauth2/token`
- `GET /oauth2/jwks`
- `POST /oauth2/introspect`
- `POST /oauth2/revoke`
- `GET /userinfo`

授权码流程：

1. 客户端访问 `/oauth2/authorize`，必须提供注册的 `redirect_uri`、合法 scopes、`code_challenge` 和 `code_challenge_method=S256`。
2. 未登录用户进入自定义登录页，以账号和 BCrypt 密码认证。
3. 登录后查询可用组织；无组织拒绝授权，单组织可直接进入，多组织显示组织选择页。
4. 组织选择写入服务端授权请求上下文，并绑定 `user_id + org_id`。
5. 授权确认页展示客户端名称、所请求 scopes 和组织名称；用户同意后签发短期一次性 authorization code。
6. 客户端使用 PKCE verifier 调用 `/oauth2/token`，获得 Access Token、ID Token（含 `openid` 时）和 Refresh Token（请求并获准时）。

Refresh Token 轮换。每次刷新用户令牌时重新检查用户和组织关系；用户已不属于该组织时拒绝刷新。

Client Credentials 只适用于已注册的机密客户端，令牌仅包含客户端身份和获准 scopes，不包含用户、组织或角色 claims。

## 6. 令牌与 Claims

Access Token 使用 JWT，RSA 私钥来自外部 PKCS#12/JKS。JWT 至少包含标准 `iss`、`sub`、`aud`、`iat`、`exp`、`scope`，以及用户令牌的：

```json
{
  "sub": "用户ID",
  "preferred_username": "account",
  "name": "用户姓名",
  "org_id": "当前组织ID",
  "org_name": "当前组织名称",
  "roles": ["角色名称"]
}
```

ID Token 仅放必要的 OIDC 身份信息和当前 `org_id`。UserInfo 返回当前用户、当前组织和该组织角色。角色名称作为 claims 暴露，不把跨组织角色放入令牌。

建议默认有效期：authorization code 5 分钟、Access Token 10 分钟、ID Token 10 分钟、Refresh Token 30 天、登录 Session 空闲 30 分钟。所有时长可配置。

## 7. 安全策略与错误处理

- Authorization Code 必须 PKCE；不实现 Password Grant。
- `redirect_uri` 精确匹配注册值；授权码单次使用且短时有效。
- 只允许注册客户端请求已登记 scopes；越权 scope 返回 `invalid_scope`。
- 登录失败使用不区分账号存在性的通用错误。
- 组织无效、用户不属于组织或关系已撤销返回 `access_denied`，不泄露数据库细节。
- `/oauth2/introspect` 和 `/oauth2/revoke` 仅允许已认证机密客户端。
- Cookie 使用 `HttpOnly`、`Secure`、适当的 `SameSite`；生产环境强制 HTTPS、CSRF 防护和安全响应头。
- 外部密钥库、issuer 或密钥别名配置错误时启动失败，禁止降级到随机密钥。
- 健康检查只暴露应用和数据库状态，不泄露密钥、用户和客户端数据。

## 8. 配置与部署

issuer、服务端口、MySQL 连接、Flyway、JWK 密钥库路径/密码/类型/别名、令牌有效期和客户端初始配置均支持外部配置。提供 local profile 使用固定开发密钥；生产必须使用外部 PKCS#12/JKS 和 HTTPS。

Higress 或后续资源服务器通过 issuer discovery/JWKS 获取公钥并校验 JWT，不需要共享私钥。issuer 必须是部署后客户端可访问的公开 HTTPS 地址。

## 9. 测试与验收

- 身份仓储：BCrypt 登录、用户不存在、组织列表、组织隔离、角色查询。
- OAuth：PKCE 授权码、错误 redirect、重复 code、refresh rotation、client credentials、scope 限制。
- OIDC：两类 discovery、ID Token claims、UserInfo、JWKS。
- 安全：未认证访问、CSRF、会话固定、跨组织 token、过期 token、撤销关系后的 refresh 拒绝。
- 使用 MySQL 8 Testcontainers 或集成 profile 验证 Flyway 与官方 JDBC schema。
- 提供启动配置样例和 curl 验收脚本。
- 全量 `mvn verify` 必须通过；不修改现有 `weather-mcp-server` 行为。

