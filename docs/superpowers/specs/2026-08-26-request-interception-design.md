# 用户上下文请求拦截与 Feign 透传 设计

## 目标

在现有 Spring AI Weather MCP Demo 上演示两种"请求拦截 → 提取用户上下文 → 通过 Feign 拦截器传递给下游"的方式，并在 `weather-service` 打印收到的用户上下文以验证透传：

1. **Bearer Token 方式**：提取入站 `Authorization: Bearer <JWT>`，HS256 验签后解析 claims，提取 `userId`、`tenantId`，通过 Feign 拦截器传递给下游。
2. **显式 Header 方式**：直接提取入站 `X-User-Id`、`X-User-Tenant`，通过 Feign 拦截器传递给下游。

两种方式通过配置开关二选一（实验切换），下游统一使用 `X-User-Id` / `X-User-Tenant` 两个 header 名。

## 技术基线

沿用现有基线（Java 21、Spring Boot 4.0.8、Spring Cloud 2025.1.3、Spring AI 2.0.1），新增：

| 技术 | 版本或选择 |
| --- | --- |
| JWT 解析 | jjwt 0.12.x（`jjwt-api` 编译期 + `jjwt-impl`、`jjwt-jackson` 运行期） |
| 签名算法 | HS256，共享密钥来自配置 |

依赖变更仅落在 `weather-mcp-server`；`weather-service` 零新依赖。

## 范围

### 包含

- 入站请求拦截（`weather-mcp-server` 侧，对全部请求生效，业务入口实际只有 `/mcp`）。
- Bearer 模式：JWT HS256 验签 + 提取 `userId`/`tenantId` 自定义 claims。
- 显式模式：读取 `X-User-Id`/`X-User-Tenant`。
- 统一的 Feign 出站拦截器：附加 `X-User-Id`/`X-User-Tenant` 到对 `weather-service` 的调用。
- `weather-service` 侧打印收到的用户上下文（INFO 日志）。
- 自动化单测 + README 手工验收说明（含测试 JWT 生成方法）。

### 不包含

- 鉴权强制：token 缺失/无效时不拒绝请求（本 demo 明确不做 MCP 认证，见 README Security Notice）。
- `Authorization` 头原样透传下游。
- 异步/虚拟线程场景下的上下文传播。
- 网关、Spring Security、其他下游服务。

## 总体架构

```text
外部客户端 (MCP Inspector / curl)
   │  POST /mcp  + 入站请求头
   ▼
weather-mcp-server (8081)
   ├─ UserContextExtractionFilter（OncePerRequestFilter）
   │     user-context.source=bearer-token:
   │       提取 Authorization: Bearer <JWT> → JwtTokenParser 验签 → claims userId/tenantId
   │     user-context.source=explicit-headers:
   │       直接读取 X-User-Id / X-User-Tenant
   │     └─ 结果写入 UserContextHolder（ThreadLocal），finally 中 clear
   ├─ Spring AI @Tool（同步执行，同线程）
   │     └─ OpenFeign 出站调用
   │           └─ UserContextFeignInterceptor：Holder 有值时附加 X-User-Id / X-User-Tenant
   ▼
weather-service (8082)
   └─ UserContextLoggingFilter：INFO 日志打印收到的 X-User-Id / X-User-Tenant
```

Feign 调用在 WebMVC 同步线程内完成，与过滤器同一线程，ThreadLocal 可靠。

## 组件设计

### weather-mcp-server 新增组件

| 组件 | 职责 | 依赖 |
| --- | --- | --- |
| `UserContext`（record） | `userId`、`tenantId` 两个 String 字段 | 无 |
| `UserContextHolder` | ThreadLocal 存取 `UserContext`，提供 `set/get/clear` | 无 |
| `UserContextExtractionFilter` | `OncePerRequestFilter`，按配置开关提取入站上下文；`finally` 中 `clear()` | `UserContextHolder`、`JwtTokenParser`、配置 |
| `JwtTokenParser` | 封装 jjwt：HS256 验签 + 提取 claims，返回 `Optional<UserContext>`；验签失败/过期/缺 claim 均返回空并记录原因 | jjwt |
| `UserContextFeignInterceptor` | 实现 `feign.RequestInterceptor`；Holder 有非空值时附加对应 header | `UserContextHolder` |

设计要点：

- 提取（入站）与透传（出站）两个关注点分离，各自可独立单测。
- 两种模式共用同一套下游 header 命名与 weather-service 打印逻辑，切换只改配置。
- 不引入 `RequestContextHolder` 依赖，Feign 拦截器只面向 `UserContextHolder`，便于单测。

### weather-service 新增组件

| 组件 | 职责 | 依赖 |
| --- | --- | --- |
| `UserContextLoggingFilter` | `OncePerRequestFilter`，对请求打印收到的 `X-User-Id`/`X-User-Tenant`（INFO 日志，值为空则跳过） | 无 |

## 配置（weather-mcp-server 的 application.yml）

```yaml
user-context:
  source: ${USER_CONTEXT_SOURCE:bearer-token}   # bearer-token | explicit-headers
  jwt:
    secret: ${USER_CONTEXT_JWT_SECRET:demo-secret-change-me-0123456789abcdef}  # HS256 需 ≥32 字节
```

## 数据流

### 方式一：Bearer Token

1. 外部客户端向 `POST /mcp` 发送请求，带 `Authorization: Bearer <JWT>`。
2. `UserContextExtractionFilter` 识别 Bearer 前缀，调用 `JwtTokenParser` HS256 验签并提取 `userId`/`tenantId` claims。
3. 结果写入 `UserContextHolder`。
4. Spring AI 工具同步调用 Feign Client。
5. `UserContextFeignInterceptor` 从 Holder 取出上下文，向出站请求附加 `X-User-Id`/`X-User-Tenant`。
6. `weather-service` 的 `UserContextLoggingFilter` 打印收到的 header 值。

### 方式二：显式 Header

1. 外部客户端向 `POST /mcp` 发送请求，带 `X-User-Id`、`X-User-Tenant`。
2. `UserContextExtractionFilter` 直接读取两个 header 写入 Holder。
3. 后续流程与方式一第 4–6 步相同。

## 错误处理与边界

| 场景 | 行为 |
| --- | --- |
| Bearer 模式：无 `Authorization` 头或非 Bearer 前缀 | DEBUG 日志，无上下文，正常继续（不返回 401） |
| Bearer 模式：验签失败 / token 过期 / 格式错误 | WARN 日志（含原因），无上下文继续 |
| Bearer 模式：claim 缺失 | 取到哪个放哪个，`null` 值不放（Feign 只加非空 header） |
| 显式模式：header 缺失 | 无上下文继续 |
| ThreadLocal 泄漏 | `finally` 中 `clear()` |
| 出站 header 污染 | Feign 拦截器仅在上下文非空时附加 header；`Authorization` 原样不透传下游 |

## 测试

沿用现有 JUnit 5 + AssertJ 风格，测试 profile 继续禁用 Eureka。

### weather-mcp-server

- `JwtTokenParserTest`：合法 token 提取 claims；错误签名拒绝；过期 token 拒绝；缺 claim 返回空。
- `UserContextExtractionFilterTest`：bearer 模式带合法 token → Holder 有值；explicit 模式带两个 header → Holder 有值；header 缺失 → Holder 空；请求结束后 Holder 被清理。
- `UserContextFeignInterceptorTest`：有上下文 → 请求头附加两 header；无上下文 → 不加任何 header。

### weather-service

- `UserContextLoggingFilterTest`：带 header 请求 → 用 logback `ListAppender` 断言日志内容；不带 → 无 INFO 输出。

### 构建

`mvn verify` 全绿。

## 手工验收（写入 README）

1. 启动 Eureka + 两个服务（`USER_CONTEXT_SOURCE` 默认 `bearer-token`）。
2. 文档给出用默认 secret 生成测试 JWT 的命令（含 `userId`/`tenantId` claims）。
3. **方式一**：MCP Inspector 或 curl 向 `/mcp` 发请求，带 `Authorization: Bearer <token>` → weather-service 日志出现 `X-User-Id`/`X-User-Tenant` 打印。
4. **方式二**：设 `USER_CONTEXT_SOURCE=explicit-headers` 重启，请求带 `X-User-Id: 1001`、`X-User-Tenant: acme` → weather-service 日志打印。
5. **成功标准**：两种模式下 weather-service 都打印出正确 userId/tenantId；切换配置后行为随之切换。
