# auth-server

独立 OAuth 2.1 / OpenID Connect 授权服务器，默认端口 `8083`。它基于只读的
`sys_user` / `sys_org` / `sys_role` / `sys_user_org_role` 表做 BCrypt 认证与组织级角色绑定，
并用 Flyway 管理自己的 `oauth2_*` 表。设计与验收标准见
[认证服务器设计](../docs/superpowers/specs/2026-09-18-auth-server-design.md)。

## 端点

| 端点 | 说明 |
| --- | --- |
| `GET /.well-known/oauth-authorization-server` | OAuth 2.1 元数据 |
| `GET /.well-known/openid-configuration` | OIDC 元数据 |
| `GET /oauth2/authorize` | 授权端点（Authorization Code + PKCE S256，必须） |
| `POST /oauth2/token` | 令牌端点（authorization_code、refresh_token、client_credentials） |
| `GET /oauth2/jwks` | 公钥集 |
| `POST /oauth2/introspect` / `POST /oauth2/revoke` | 仅限已认证客户端 |
| `GET /userinfo` | OIDC UserInfo（需要 `openid` scope） |
| `GET /`、`/login`、`/organizations`、`/consent` | Vue SPA 页面（转发到 `index.html`） |
| `GET /admin/clients`、`/admin/clients/new`、`/admin/clients/{clientId}` | 平台管理员客户端管理页面（SPA 外壳，转发到 `index.html`） |
| `GET /api/auth/session`、`POST /api/auth/login`、`POST /api/auth/logout` | SPA 登录会话 API |
| `GET /api/organizations`、`POST /api/organizations` | 组织选择 API（单组织自动绑定） |
| `GET /api/consent` | 授权确认页数据 API |
| `/api/admin/clients` | 平台管理员客户端管理 API（列表/创建/编辑/轮换 secret/启停/删除） |
| `GET /actuator/health` | 仅返回聚合状态（不包含组件明细） |

## 配置

全部配置都可外部覆盖（环境变量或启动参数）：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8083` |
| `auth.issuer` | `AUTH_ISSUER` | `http://localhost:8083` |
| `auth.resources.weather-mcp` | `WEATHER_MCP_RESOURCE` | `http://localhost:8081/mcp` |
| `spring.datasource.url` | `DB_URL` | `jdbc:mysql://localhost:3306/auth?...` |
| `spring.datasource.username` / `password` | `DB_USERNAME` / `DB_PASSWORD` | `root` / 空 |
| `auth.keystore.location` | `AUTH_KEYSTORE_PATH` | 空（启动失败） |
| `auth.keystore.password` | `AUTH_KEYSTORE_PASSWORD` | 空（启动失败） |
| `auth.keystore.type` | `AUTH_KEYSTORE_TYPE` | `PKCS12` |
| `auth.keystore.alias` | `AUTH_KEYSTORE_ALIAS` | 空（启动失败） |
| `auth.oauth.authorization-code-ttl` | `AUTH_AUTHORIZATION_CODE_TTL` | `PT5M` |
| `auth.oauth.access-token-ttl` | `AUTH_ACCESS_TOKEN_TTL` | `PT10M` |
| `auth.oauth.id-token-ttl` | `AUTH_ID_TOKEN_TTL` | `PT10M` |
| `auth.oauth.refresh-token-ttl` | `AUTH_REFRESH_TOKEN_TTL` | `P30D` |
| `auth.oauth.session-idle-timeout` | `AUTH_SESSION_IDLE_TIMEOUT` | `PT30M` |
| `auth.admin.accounts` | `AUTH_ADMIN_ACCOUNTS` | 空（无平台管理员） |

外部密钥库缺失、类型错误、口令错误或 alias 不存在时应用直接启动失败，不会退回随机密钥。

### 客户端管理

平台管理员通过 auth-web 的 `/admin/clients` 管理 OAuth 客户端，写操作走 `/api/admin/clients`。
管理员账号由 `AUTH_ADMIN_ACCOUNTS` 指定（逗号分隔，例如 `AUTH_ADMIN_ACCOUNTS=alice`），只有这些
账号能访问管理 API；secret 只在创建或轮换时显示一次，库里存 `{bcrypt}` 哈希。
平台管理员可以是无组织账号（平台级角色不来自 `sys_user_org_role`）：这类账号登录后直接进入
首页，不受组织绑定影响，未配置在 `AUTH_ADMIN_ACCOUNTS` 里的账号访问 `/admin/**` 会被送回
组织选择页。

生产客户端不再手工 INSERT `oauth2_registered_client`（V2/V3 种子仅用于本地开发）。停用与删除
会清理该客户端的授权与同意记录；已签发的自包含 JWT 在到期前仍然有效。

管理面与协议端点共用同一个仓储 schema，但走不同的读路径：协议端点在
`ConfiguredRegisteredClients` 装饰器里过滤掉被停用的客户端，管理面直接读原始仓储，因此停用的
客户端在管理页仍然可见、可重新启用。客户端展示类型（`web` / `machine` / `public` / `custom`）
由 grant types 与认证方式推导。

## 本地运行

1. 准备数据库（Flyway 在启动时自动建 `oauth2_*` 表）：

   ```bash
   mysql -uroot -e 'CREATE DATABASE IF NOT EXISTS auth CHARACTER SET utf8mb4'
   ```

   交互式登录还需要业务身份表（`sys_user` 等）。本地体验可直接导入测试 fixture，
   生产环境请指向业务库：

   ```bash
   mysql -uroot auth < auth-server/src/test/resources/db/sys-identity-fixtures.sql
   ```

2. 生成开发用签名密钥（不要在生产复用）：

   ```bash
   mkdir -p local
   keytool -genkeypair -alias auth-server-local -keyalg RSA -keysize 2048 \
     -storetype PKCS12 -keystore local/auth-server-local.p12 \
     -storepass changeit -keypass changeit -validity 3650 \
     -dname "CN=localhost, OU=development, O=example"
   ```

3. 启动：

   ```bash
   DB_URL='jdbc:mysql://localhost:3306/auth?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \
   DB_USERNAME=root DB_PASSWORD='' \
   AUTH_KEYSTORE_PATH=file:./local/auth-server-local.p12 \
   AUTH_KEYSTORE_PASSWORD=changeit \
   AUTH_KEYSTORE_ALIAS=auth-server-local \
   mvn -pl auth-server spring-boot:run
   ```

4. 前端页面由 `auth-web/` 构建（`mvn -pl auth-server verify` 会自动执行）：
   - 只改后端时用 `-DskipFrontend=true` 跳过前端构建；
   - 只改前端时执行 `cd auth-web && npm install && npm run dev`，Vite（5173）会把
     `/api`、`/oauth2`、`/.well-known`、`/userinfo`、`/actuator` 代理到 `8083`；
   - 完整体验（含 MCP 客户端发起的授权流程）请先构建前端再启动 8083。

5. 验收：`curl -s http://localhost:8083/actuator/health` 与 `scripts/auth-server-smoke.sh`
   （见 [测试文档](../docs/auth-server-testing.md)）。

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

## 客户端注册

`V2__seed_local_clients.sql` 只预置本地开发客户端。新客户端请通过
[客户端管理](#客户端管理) 创建，不再手工 INSERT；secret 只保存编码值（`{bcrypt}...`），
明文只在创建或轮换时显示一次：

| 客户端 | 流程 | 本地明文 secret |
| --- | --- | --- |
| `auth-web-public` | authorization_code + PKCE + refresh_token | `auth-web-secret` |
| `auth-machine` | client_credentials | `auth-machine-secret` |
| `weather-mcp-inspector` | authorization_code + PKCE（公钥客户端） | 无 |

> 客户端 id 中的 `public` 是历史命名：Spring Authorization Server 只对已认证客户端签发
> refresh token，而设计要求用户端获得可轮换 refresh token，因此该客户端使用
> `client_secret_basic` 注册，同时仍然强制 PKCE。

注册示例（生产请替换 secret 与 redirect URI）：

```sql
-- secret 用 {bcrypt} 前缀保存；生成方式见 docs/auth-server-testing.md
INSERT INTO oauth2_registered_client (...) VALUES (...);
```

## 与 weather-mcp-server 对接

`weather-mcp-server` 是资源服务器，只校验本服务器签发的 JWT，不再内嵌授权服务器：

- 带 `weather:read` scope 的 access token 会把 `aud` 绑定到 `auth.resources.weather-mcp`
  （默认 `http://localhost:8081/mcp`）。
- 资源服务器通过 `AUTH_SERVER_URL`（默认 `http://localhost:8083`）校验 issuer 与
  `/oauth2/jwks` 公钥，并检查 `aud`；两端配置必须指向同一个 issuer。
- MCP Inspector 使用 `weather-mcp-inspector`（授权码 + PKCE），回调地址在
  `V3__seed_weather_mcp_client.sql` 中注册。

## 测试

```bash
mvn -pl auth-server verify     # 模块测试（Testcontainers 启动 MySQL 8）
mvn verify                     # 全仓库
```

## 安全注意事项

- 生产必须使用外部 PKCS#12/JKS、HTTPS 和真实 issuer；本仓库中的密钥与客户端 secret
  仅用于本地开发。
- 组织选择绑定在服务端会话与授权记录中，客户端无法指定组织或角色。
- refresh token 30 天、单次使用（轮换）；刷新时重新校验组织关系，关系撤销后返回
  `invalid_grant`。
- `/oauth2/introspect` 与 `/oauth2/revoke` 仅允许已认证客户端访问。
