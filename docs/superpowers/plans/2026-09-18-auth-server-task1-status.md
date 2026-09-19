# Auth Server 执行状态（Task 1 记录，已被完成状态取代）

记录时间：2026-09-19

> 本文档最初记录 Task 1 中断时的状态。该中断已恢复，Task 1–7 全部完成并通过验收，
> 下面是最终状态；当时的阻塞（Maven 本地缓存写权限、审批服务故障）已消失。

## 最终状态

| 任务 | 提交 | 结果 |
| --- | --- | --- |
| Task 1 新增 Maven 模块与运行配置 | `90b9800` | 完成 |
| Task 2 Flyway OAuth schema 与只读身份 Repository | `2ba8aac` | 完成 |
| Task 3 Authorization Server、JDBC Repository 与 JWK | `8da7ea6` | 完成 |
| Task 4 用户登录与组织绑定授权上下文 | `cc87cd0` | 完成 |
| Task 5 OAuth/OIDC 流程与完整 claims | `b76414f` | 完成 |
| Task 6 生命周期安全策略与关系撤销 | `4fdf971` | 完成 |
| Task 7 集成验证、文档与验收脚本 | `7838672`（+ 审查补充 `e4801c1`） | 完成 |

## 验收证据

- `mvn verify`（全仓库）→ BUILD SUCCESS：auth-server 54、weather-service 8、weather-mcp-server 19。
- `scripts/auth-server-smoke.sh` 对真实进程实跑通过：discovery issuer 一致、JWKS 仅公开 RSA 公钥、
  client_credentials 取令牌、introspection `active=true`、revoke 后 `active=false`、
  错误客户端密钥返回 401。
- 交互式流程实跑通过：登录 → 组织选择 → 授权确认页 → 授权码 → 换令牌（access + ID + refresh）
  → `/userinfo` 返回 `sub` / `preferred_username` / `org_id` / `org_name` / `roles`。
- 规格 §7 的失败路径均有测试：缺少 PKCE、错误 verifier、redirect 不匹配、重复 code、
  关系撤销后的 refresh、过期令牌、无客户端认证的 introspect/revoke、无 CSRF 的自定义页面提交。

## 与计划/设计的主要偏差（rulings）

1. `auth-web-public` 以**机密客户端**（`client_secret_basic`，本地明文 `auth-web-secret`）注册，
   但仍强制 PKCE。原因：Spring Authorization Server 7 不向 public client 签发 refresh token，
   也不允许 public client 在 refresh 请求中认证，而设计要求用户端获得 30 天可轮换 refresh token；
   该客户端注册的 redirect URI 是服务端 OAuth2 登录回调，持有 secret 是可行的。客户端 id 中的
   `public` 属于历史命名。
2. 授权确认页使用自定义的 `/oauth2/consent`（展示客户端名称、请求的 scopes 与当前组织），
   因为设计 §1/§5 要求自定义确认页，而原任务清单未包含该文件。
3. JDBC 授权属性需要自定义 Jackson 3 `JsonMapper` 类型白名单（`com.example.auth.security.*`
   与 `java.util.ArrayList`），因为 Boot 4 / Security 7 默认拒绝应用自定义类型。
4. `auth.oauth.*` 时长通过 `ConfiguredRegisteredClients` 装饰器统一施加到所有客户端；
   ID token 时长跟随 access token（SAS 的 `JwtGenerator` 行为）。
5. 新增 `spring-boot-starter-actuator`，仅暴露 `/actuator/health` 聚合状态（规格 §7）。
