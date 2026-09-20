# 客户端管理（Client Management）设计

> 日期：2026-09-20  
> 状态：待评审  
> 范围：auth-server 新增平台管理员专用的客户端管理 JSON API，auth-web 新增对应管理页面；
> OAuth 2.1 / OIDC 协议行为、令牌格式、`oauth2_registered_client` 表结构和现有种子客户端、
> `weather-mcp-server` 均不变

## 1. 目标与边界

平台管理员在 auth-web 内完成 OAuth 客户端的全生命周期管理：查看列表与详情、创建、编辑、
轮换 client secret、启用/停用、删除，并让每次变更都有审计记录。所有写入统一经过
`RegisteredClientRepository`（`JdbcRegisteredClientRepository`），不再由运维手写 SQL，
也不再把 secret 放进 Flyway 迁移。

本期包含：

- auth-server：`/api/admin/clients` JSON API、`ClientManagementService`、查询与审计仓储、
  `ROLE_PLATFORM_ADMIN` 权限、V4 审计表迁移。
- auth-web：`/admin/clients`、`/admin/clients/new`、`/admin/clients/:clientId` 三个页面，
  列表搜索、创建表单、一次性 secret 展示、危险操作区、中英文文案。
- 测试：后端 MockMvc 与 Testcontainers MySQL 集成测试；前端 Vitest。

本期不包含：

- 分页、客户端缓存、secret 双活/宽限期、`client_secret_expires_at` 管理、token TTL 在线编辑。
- RFC 7591 / OIDC 动态客户端注册端点（对外自助注册）。
- 组织/租户归属：client 是全局平台资源，与组织无关。
- 外部身份系统改动：平台管理员身份来自配置。

## 2. 关键决策与依据

**决策 1：管理面留在 auth-server，不引入独立管理应用。**

本仓库是一个 jar、一个 MySQL schema、一套同源 SPA。管理 API 放进 auth-server 可以直接复用
现有 session、CSRF、`/api/**` 约定和 `ApiError` 错误码，部署形态不变。独立管理应用只带来
额外的工程、构建和共享 schema 契约，对当前规模是过度设计。

**决策 2：不使用框架内置的 RFC 7591 端点做管理接口。**

Spring Security 7.0 的 `OAuth2ClientRegistrationEndpointConfigurer` 面向自助注册：服务端固定
生成 client_id 和 secret、不能自定义、不能编辑、不能删除，调用方还必须先取得 scope 恰好为
`client.create` 的 bearer token。它覆盖不了管理诉求，保留为将来对外开放自助注册的扩展点。

**决策 3：平台管理员来自配置 `auth.admin.accounts`。**

现有身份模型只有"用户 × 组织"的角色（`sys_user_org_role`），没有平台级角色，而 `sys_*` 表
由外部系统所有，auth-server 只读。本期的平台管理员由部署配置指定：命中
`auth.admin.accounts` 的账号在登录成功后额外获得 `ROLE_PLATFORM_ADMIN`。等身份系统提供平台级
角色后，只需替换这一处判定来源。

**决策 4：启用/禁用状态存进 `client_settings` 的自定义 key。**

框架的 `ClientSettings.withSettings(Map)` 会把未知 key 原样往返，`JdbcRegisteredClientRepository`
读取时用同一机制还原。因此禁用状态以 `com.example.auth.client.enabled`（Boolean，缺省即启用）
写在 `client_settings` JSON 内，不需要改动官方表结构；读取侧的过滤放在已有的
`ConfiguredRegisteredClients` 装饰器里。备选方案是给 `oauth2_registered_client` 加 `enabled` 列，
但框架的行映射不读该列，读取路径要多一次查询，收益不足。

**决策 5：secret 服务端生成、bcrypt 存储、只展示一次。**

创建与轮换时由服务端用 `SecureRandom` 生成 secret，使用现有 `PasswordEncoder` bean 编码成
`{bcrypt}...` 后交给 `RegisteredClientRepository.save()`；明文只在创建/轮换的响应里出现一次。
列表、详情、日志、审计都不包含 secret。本期不做双 secret 宽限期，轮换即旧 secret 立即失效。

## 3. 架构与组件

```text
auth-web（Vue 3 SPA，同源）
  └── /admin/clients  →  /api/admin/clients        session + CSRF
                                │
                                ▼
                    ClientAdminController（JSON）
                                │
                                ▼
                    ClientManagementService（唯一写入口）
                      ├── JdbcRegisteredClientRepository（原始仓储，读写）
                      ├── ClientQueryRepository（JdbcTemplate 列表/搜索/清理）
                      └── ClientAuditRepository（JdbcTemplate 审计）

协议端点（Order 1 安全链，不变）
  └── RegisteredClientRepository = ConfiguredRegisteredClients(装饰器：TTL 覆盖 + 禁用过滤)
```

包结构：新增 `com.example.auth.clientadmin`（controller、service、两个 JdbcTemplate 仓储、DTO），
修改 `com.example.auth.config`（属性、安全链、仓储 bean 拆分）、`com.example.auth.security`
（平台管理员权限）、`com.example.auth.web.AuthApiController`（session 增加字段）。

仓储 bean 拆分：`JdbcRegisteredClientRepository` 注册为独立 bean，`RegisteredClientRepository`
bean 仍返回 `ConfiguredRegisteredClients` 装饰器供协议端点使用；管理面直接注入原始仓储，
这样禁用中的客户端在管理页仍然可见、可重新启用，而协议端点通过装饰器把禁用客户端视为不存在。

权限链路：

- `AuthServerProperties` 增加 `Admin(List<String> accounts)`，对应 `auth.admin.accounts`
  （逗号分隔，默认空；本地可用 `AUTH_ADMIN_ACCOUNTS=alice`）。
- `IdentityAuthenticationProvider` 认证通过后，账号命中配置时在 `Authentication` 的 authorities
  里追加 `ROLE_PLATFORM_ADMIN`；`OrganizationBindingService` 重新绑定组织时已保留原 authorities，
  权限不会丢失。
- `SecurityConfig`（Order 2）在 `anyRequest().authenticated()` 之前加
  `.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")`；未登录返回 401 JSON，
  无权限返回 403 JSON。
- `/api/auth/session` 的 `SessionResponse` 增加 `platformAdmin` 字段，仅用于前端显隐入口；
  服务端始终强校验。
- SPA 路由守卫在"已登录但未绑定组织"的检查之前放行 `platformAdmin` 访问 `/admin/**`；
  非管理员访问 `/admin/**` 重定向回首页。

## 4. API 契约

所有端点挂在 `/api/admin/clients` 下，请求与响应都是 JSON，写请求带 `X-XSRF-TOKEN`（沿用现有
`api()` 客户端）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 列表，支持 `query` 按 client_id / name 模糊搜索，返回全量（不分页） |
| GET | `/{clientId}` | 详情 |
| POST | `/` | 创建，响应包含一次性明文 secret |
| PUT | `/{clientId}` | 编辑，client_id 不可变，不触碰 secret |
| POST | `/{clientId}/secret` | 轮换 secret，响应包含一次性明文 secret |
| POST | `/{clientId}/disable` | 停用 |
| POST | `/{clientId}/enable` | 启用 |
| DELETE | `/{clientId}` | 删除 |

列表项字段：`clientId`、`clientName`、`type`、`grantTypes`、`scopes`、`enabled`、
`updatedAt`、`updatedBy`（后两者来自审计，无记录时为 null）。

详情字段：在列表项基础上增加 `clientAuthenticationMethods`、`redirectUris`、
`postLogoutRedirectUris`、`requireProofKey`、`requireAuthorizationConsent`、
`clientIdIssuedAt`、`clientSecretExpiresAt`，以及只读的 `tokenSettings`
（access / refresh / authorization-code TTL、id-token 签名算法、令牌格式），TTL 值展示的是
经过 `ConfiguredRegisteredClients` 覆盖后的生效值。

创建请求字段：`clientId`、`clientName`、`type`（`web` / `machine` / `public`）、
`redirectUris`、`postLogoutRedirectUris`、`scopes`、`requireAuthorizationConsent`。
`type` 只是表单预设，创建后展示的类型始终由 grant types 与认证方式推导，匹配不到预设的
组合显示为 `custom`。预设内容：

| 类型 | grant types | 客户端认证 | PKCE |
| --- | --- | --- | --- |
| `web` | authorization_code + refresh_token | client_secret_basic | 必须开启 |
| `machine` | client_credentials | client_secret_basic | 不适用 |
| `public` | authorization_code | none | 必须开启 |

公共客户端不含 refresh_token：Spring Authorization Server 的 refresh token 流程要求客户端认证
（与 `V2__seed_local_clients.sql` 中的说明一致），需要 refresh token 的浏览器客户端应按
机密客户端 + PKCE 注册。

编辑请求字段：`clientName`、`redirectUris`、`postLogoutRedirectUris`、`scopes`、
`grantTypes`、`clientAuthenticationMethods`、`requireAuthorizationConsent`。
`clientId` 不可变；授权方式与认证方式可以修改，但必须满足组合校验：

- `client_credentials` 与 `refresh_token` 要求认证方式不是 `none`。
- 认证方式为 `none` 时只能是 `authorization_code`，且 `requireProofKey` 强制为 true。
- 机密客户端可以关闭 `requireProofKey`，默认仍为 true。
- 从机密改为公共（`none`）时清除 secret；从公共改为机密后通过轮换动作生成新 secret。

校验规则：`clientId` 匹配 `^[a-z0-9][a-z0-9-]{2,63}$` 且唯一；redirect URI 必须是绝对 URI、
不含 fragment，`https` 之外只允许回环地址（`127.0.0.1` / `localhost`）；`authorization_code`
客户端至少一个 redirect URI；scope 匹配 `^[A-Za-z0-9:_.-]{1,64}$` 且去重。

错误响应沿用 `ApiError` 结构，新增可选的 `details` 数组用于字段级提示（向后兼容）：

```json
{"error":"invalid_client_metadata","details":[{"field":"redirectUris","code":"invalid_uri"}]}
```

错误码：`client_not_found`（404）、`client_id_taken`（409）、`invalid_client_metadata`（400）、
`access_denied`（403）。前端按错误码 + 字段码映射 i18n 文案。

## 5. 数据与 secret 生命周期

新增迁移 `V4__oauth2_registered_client_audit.sql`（只加审计表，不改官方表结构）：

```sql
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

`action` 取值：`CREATE`、`UPDATE`、`ROTATE_SECRET`、`ENABLE`、`DISABLE`、`DELETE`。
`changed_fields` 只记录字段名列表，不记录任何值；审计行不设外键，客户端删除后仍然保留。

创建流程：生成内部 id（UUID）→ 生成 secret（公共客户端跳过）→ 用 `PasswordEncoder` 编码
secret → 组装 `RegisteredClient`（client_id 来自请求，scopes / URIs / grant types / 认证方式
来自请求，`tokenSettings` 按 `auth.oauth.*` 写入）→ `save()` → 写 `CREATE` 审计 → 响应返回一次
明文 secret。

轮换流程：`findByClientId` → `RegisteredClient.from(existing).clientSecret(encode(newSecret))`
→ `save()` → 写 `ROTATE_SECRET` 审计 → 响应返回一次明文 secret。旧 secret 立即失效。

停用流程（单事务）：把 `com.example.auth.client.enabled=false` 写回 `client_settings` 并
`save()` → `DELETE FROM oauth2_authorization WHERE registered_client_id = ?` →
`DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?` → 写 `DISABLE` 审计。
装饰器随后对该客户端的 `findById` / `findByClientId` 返回 null，令牌端点与授权端点立即把它
视为不存在的客户端。启用流程：把该 key 置回 true 或移除，写 `ENABLE` 审计。

删除流程（单事务）：执行与停用相同的清理，再删除 `oauth2_registered_client` 行，写 `DELETE`
审计（审计行保留）。

已签发的自包含 JWT 访问令牌在到期前仍然有效，不受停用/删除影响；这是 JWT 的固有限制，UI 的
确认文案必须说明。停用/删除会清除授权与同意记录，重新启用后用户需要重新授权。

列表/搜索由 `ClientQueryRepository` 用 JdbcTemplate 完成，SELECT 列表中不出现 `client_secret`
列；详情读取走原始 `JdbcRegisteredClientRepository`，保证 settings 反序列化与协议端点一致。

## 6. UI 流程

路由与页面：

- `/admin/clients`：列表页。表格展示 client_id、名称、类型、grant types、scopes、启用状态、
  最近变更时间与操作人；顶部搜索框和"新建客户端"按钮；禁用中的客户端以 badge 区分。
- `/admin/clients/new`：创建页。先选类型预设，再按类型显示字段（`web`/`public` 显示 redirect
  URIs 与 post-logout URIs，`machine` 显示 scopes 提示）；提交成功后弹出"client_id + secret
  仅显示一次"对话框，提供复制按钮和"我已保存"确认，未确认前不跳转。
- `/admin/clients/:clientId`：详情页。上半部分为编辑表单，下半部分为危险操作区：轮换 secret
  （确认后一次性展示新 secret）、停用/启用、删除（要求输入 client_id 二次确认）。

所有错误按 `error` + `details[].code` 映射到字段旁或页面级提示；仓库状态、权限变化等全局事件
沿用现有 401 跳登录的行为。界面文案补齐 zh/en 两份 i18n 资源，视觉沿用现有布局组件，
不引入新的设计系统。

## 7. 测试与验收

后端（MockMvc + Spring Security Test）：

- 未登录访问 `/api/admin/**` 返回 401；非平台管理员返回 403。
- 创建成功：响应含明文 secret；数据库中 `client_secret` 是 `{bcrypt}` 前缀的哈希；
  列表和详情响应不含 secret；审计写入 `CREATE`。
- clientId 重复返回 409 `client_id_taken`；非法 redirect URI / scope 返回 400 与字段级 details。
- 编辑不改变 client_id 与 secret；非法 grant type / 认证方式组合被拒绝。
- 轮换后：旧 secret 走 client_credentials 失败，新 secret 成功；审计写入 `ROTATE_SECRET`。
- 停用后：令牌端点拒绝；`oauth2_authorization` 与 `oauth2_authorization_consent` 已清理；
  管理列表与详情仍可见；启用后可以重新取得 token。
- 删除后：注册行消失，审计保留，令牌端点拒绝。

集成测试（Testcontainers MySQL，沿用 `AuthServerMySqlIntegrationTest` 模式）：V4 迁移可执行；
`com.example.auth.client.enabled` 自定义 setting 经 `JdbcRegisteredClientRepository` 往返正确；
查询仓储返回正确的列表与排序。

前端（Vitest）：路由守卫对非管理员重定向、对平台管理员放行 `/admin/**`；列表渲染与搜索；
创建表单校验；一次性 secret 对话框在确认前不跳转。

验收路径：`AUTH_ADMIN_ACCOUNTS=alice` 启动 → alice 登录 → 创建一个机器客户端 →
用 client_credentials 取得 token → 轮换 → 旧 secret 失败、新 secret 成功 → 停用 → 请求被拒 →
删除 → 注册行消失且审计完整。

## 8. 风险与限制

- 自包含 JWT 无法即时撤销：停用/删除只影响后续令牌签发与交换，已签发的访问令牌到期前仍有效。
- `JdbcRegisteredClientRepository` 的 `save()` 是"先查后写"，没有乐观锁；平台管理员的低频操作
  可以接受，后续如需并发保护要自研仓储。
- 该仓储每次读取都查库且无缓存；本期不加缓存，避免引入管理写路径的失效问题。
- `ClientQueryRepository` 的 SQL 直接依赖 `oauth2_registered_client` 列名，框架升级时要同步核对。
