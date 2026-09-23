# auth-server 测试与验收

本文档说明 `auth-server` 的自动化测试、本地验收脚本和手工检查步骤。设计与验收标准见
[认证服务器设计](superpowers/specs/2026-09-18-auth-server-design.md)。

## 自动化测试

```bash
mvn -pl auth-server verify   # 仅 auth-server
mvn verify                   # 全仓库（weather-service、weather-mcp-server、auth-server）
```

测试需要 Docker（Testcontainers 启动 `mysql:8.4`）；`test` profile 的数据源指向不可达端口，
任何测试都不会连接本机或环境的真实数据库。

| 测试类 | 覆盖内容 |
| --- | --- |
| `AuthServerApplicationTest` | 应用名、端口与 `auth.oauth.*` 默认时长 |
| `JdbcIdentityRepositoryTest` | BCrypt 密码原样读取、组织隔离、角色隔离、Flyway schema 与种子客户端 |
| `KeyStoreConfigTest` | 密钥库缺失 / 口令错误 / alias 不存在时启动失败 |
| `AuthorizationServerMetadataTest` | discovery、OIDC 元数据、JWKS、JDBC 客户端反序列化、MCP Inspector 客户端注册 |
| `IdentityAuthenticationProviderTest` | BCrypt 认证成功/失败、统一错误、用户 ID 不取自请求 |
| `AuthenticationEntryPointTest` | 入口点分流（API 401 / HTML 302 `/login`）、组织绑定后续跑授权请求 |
| `AuthApiControllerTest` | JSON 会话探测、登录成功/失败 401、缺少 CSRF 403、登出 204 |
| `OrganizationApiControllerTest` | 组织列表、单组织自动绑定、越权选择 403 |
| `ConsentApiControllerTest` | consent 数据接口的 scope 拆分/去重/过滤与未知客户端回退 |
| `SpaRoutingTest` | SPA 路由转发到 `index.html`（`no-cache`）、构建产物的外壳内容 |
| `OAuthAuthorizationCodeFlowTest` | PKCE 授权码全流程、错误 verifier、重复 code、redirect 不匹配、缺少 PKCE、`aud` 绑定 MCP 资源 |
| `ClientCredentialsFlowTest` | 机器客户端取令牌、scope 校验、错误凭据 401、`aud` 绑定 MCP 资源 |
| `UserInfoTest` | UserInfo 返回用户/组织/角色、无令牌 401、关系撤销后 401 |
| `TokenLifecycleSecurityTest` | 10 分钟 access token、过期拒绝、refresh 轮换、关系撤销、撤销令牌、introspect/revoke 客户端认证 |
| `AuthServerMySqlIntegrationTest` | MySQL 8 上 Flyway 三表、client_credentials 与授权码流程的持久化 |

## Smoke 脚本

```bash
scripts/auth-server-smoke.sh                 # 默认 http://localhost:8083
scripts/auth-server-smoke.sh https://auth.example.com
```

脚本依次检查：discovery issuer 一致、JWKS 含且仅含公开 RSA 公钥、client_credentials 取令牌、
introspection 返回 `active=true`、revoke 后 `active=false`、错误客户端密钥返回 401。
任一检查失败脚本以非零退出；脚本不会把令牌写入文件或标准输出。

依赖：`curl`、`jq`。

## SPA 页面验收（手动）

1. `mvn -pl auth-server verify`（构建前端）后启动 8083。
2. 访问 `http://localhost:8083/login`：应看到品牌分栏布局的登录页，右上角可切换中文/EN。
3. 用 MCP Inspector 走完整授权码流程：登录 → 组织选择 → 授权确认 → 回到客户端拿到 token。
4. 刷新 `/login`、`/organizations`、`/consent`、`/` 任意一次都不应出现 404。
5. 浏览器 DevTools 中确认所有请求同源、无 CORS 预检；窄屏（< 768px）下品牌分栏折叠。

## 手工验收

```bash
# 0. 本机数据库先导入业务身份表 fixture（生产环境用真实业务库）
docker exec -i <mysql容器> mysql -uroot auth < auth-server/src/test/resources/db/sys-identity-fixtures.sql

# 1. 元数据
curl -s http://localhost:8083/.well-known/oauth-authorization-server | jq .issuer
curl -s http://localhost:8083/oauth2/jwks | jq '.keys[0].kty'
curl -s http://localhost:8083/actuator/health   # 只返回聚合状态

# 2. 机器客户端取令牌（输出不落地）
ACCESS_TOKEN=$(curl -s -XPOST http://localhost:8083/oauth2/token \
  --user auth-machine:auth-machine-secret \
  -d grant_type=client_credentials -d scope=weather:read | jq -r .access_token)

# 3. introspection
curl -s -XPOST http://localhost:8083/oauth2/introspect \
  --user auth-machine:auth-machine-secret -d "token=$ACCESS_TOKEN" | jq .active

# 3.1 weather:read token 必须 audience 绑定到 MCP 资源
curl -s -XPOST http://localhost:8083/oauth2/introspect \
  --user auth-machine:auth-machine-secret -d "token=$ACCESS_TOKEN" | jq .aud
# 预期：["http://localhost:8081/mcp"]

# 4. 浏览器授权码流程
#    浏览器打开 /oauth2/authorize?... 并观察：登录页 → 组织选择页 → 授权确认页（/oauth2/consent，
#    展示客户端名称、请求的 scopes 与当前组织）→ 回调携带 code
```

授权码流程需要注册的 `redirect_uri`、PKCE `code_challenge`（S256）和已登记 scopes；
缺少 PKCE、redirect 不匹配、重复使用授权码都会失败（`invalid_request` / `invalid_grant`）。

## 本地开发凭据（仅限本机）

| 用途 | 值 |
| --- | --- |
| 用户 | `alice` / `alice-password`、`bob` / `bob-password`（测试 fixture） |
| 平台管理员（不属于任何组织） | `superadmin` / `test@123..`（测试 fixture） |
| `auth-web-public` secret | `auth-web-secret` |
| `auth-machine` secret | `auth-machine-secret` |
| `weather-mcp-inspector` | 公钥客户端（无 secret，授权码 + PKCE） |
| 测试签名密钥 | `auth-server/src/test/resources/keystore/auth-server-test.p12`，口令 `changeit` |

生产必须改用外部 PKCS#12/JKS、HTTPS、真实 issuer，并通过密钥管理注入客户端明文 secret；
上述值不得出现在生产环境。

## 生成客户端 secret

```bash
htpasswd -bnBC 10 "" '实际密钥' | tr -d ':\n' | sed 's/^/$2a$/{ }'   # 或使用 BCryptPasswordEncoder
```

Spring Security 的 `DelegatingPasswordEncoder` 期望 `{bcrypt}$2a$...` 形式；写入初始化 SQL 前
确认前缀存在。

## 客户端管理验收

1. `AUTH_ADMIN_ACCOUNTS=alice,superadmin mvn -pl auth-server spring-boot:run`，用 alice 登录 auth-web。
   平台管理员必须出现在 `AUTH_ADMIN_ACCOUNTS` 里，否则 `/api/admin/**` 返回 403，前端会把
   `/admin/clients` 送回组织选择页（看起来像"没有可用的组织"）。`superadmin` 不属于任何组织，
   登录后直接回首页，从首页进入“客户端管理”。
2. 首页进入“客户端管理” → 新建机器客户端（scopes 填 `weather:read`），保存弹窗里的 secret。
3. 用该 client 走 client_credentials 取得 token：
   `curl -u <client_id>:<secret> -d 'grant_type=client_credentials&scope=weather:read' http://localhost:8083/oauth2/token`
4. 在详情页轮换 secret：旧 secret 立即失败，新 secret 成功。
5. 停用客户端：token 端点返回 401；列表仍可见且可重新启用。
6. 删除客户端：注册行消失，`oauth2_registered_client_audit` 中保留 CREATE/DISABLE/DELETE 记录。

权限与错误码：未登录访问 `/api/admin/**` 返回 401；非平台管理员返回 403 `access_denied`。
非法的 client_id、redirect URI 或 scope 返回 400 `invalid_client_metadata`，并带字段级
`details`；重复的 client_id 返回 409 `client_id_taken`，未知客户端返回 404 `client_not_found`。
