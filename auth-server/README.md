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
| `GET /login`、`GET/POST /organizations` | 自定义登录页与组织选择页 |
| `GET /actuator/health` | 仅返回聚合状态（不包含组件明细） |

## 配置

全部配置都可外部覆盖（环境变量或启动参数）：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8083` |
| `auth.issuer` | `AUTH_ISSUER` | `http://localhost:8083` |
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

外部密钥库缺失、类型错误、口令错误或 alias 不存在时应用直接启动失败，不会退回随机密钥。

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

4. 验收：`curl -s http://localhost:8083/actuator/health` 与 `scripts/auth-server-smoke.sh`
   （见 [测试文档](../docs/auth-server-testing.md)）。

## 客户端注册

`V2__seed_local_clients.sql` 只预置本地开发客户端，生产客户端由运维 SQL 注册，
secret 只保存编码值（`{bcrypt}...`），明文通过密钥管理或部署注入：

| 客户端 | 流程 | 本地明文 secret |
| --- | --- | --- |
| `auth-web-public` | authorization_code + PKCE + refresh_token | `auth-web-secret` |
| `auth-machine` | client_credentials | `auth-machine-secret` |

> 客户端 id 中的 `public` 是历史命名：Spring Authorization Server 只对已认证客户端签发
> refresh token，而设计要求用户端获得可轮换 refresh token，因此该客户端使用
> `client_secret_basic` 注册，同时仍然强制 PKCE。

注册示例（生产请替换 secret 与 redirect URI）：

```sql
-- secret 用 {bcrypt} 前缀保存；生成方式见 docs/auth-server-testing.md
INSERT INTO oauth2_registered_client (...) VALUES (...);
```

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
