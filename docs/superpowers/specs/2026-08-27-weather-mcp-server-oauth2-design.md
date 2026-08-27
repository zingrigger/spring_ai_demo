# Weather MCP Server OAuth 2.1 认证设计

> 日期：2026-08-27
> 状态：设计中（待评审）
> 关联模块：`weather-mcp-server`

## 目标

给 `weather-mcp-server` 实现符合 MCP 规范的 **OAuth 2.1** 认证与授权，采用官方推荐方案：MCP Server 在 OAuth 模型中同时扮演 **授权服务器（Authorization Server）** 与 **资源服务器（Resource Server）**。

- 所有 MCP 工具调用（`POST /mcp`）必须携带有效 `Authorization: Bearer <access token>`，否则返回 `401` 并给出符合 RFC 9728 的挑战头。
- 支持两种 OAuth 授权流程：`authorization_code + PKCE`（真实交互式 MCP 客户端）与 `client_credentials`（机器对机器，便于 curl 快速验证）。
- 用 OAuth `scope` 做工具级授权：`weather:read`。
- 同时清理上一会话遗留、当前已破坏构建的「用户上下文透传」过期产物（见「清理范围」）。

## 背景与现状

- 项目为 Spring Boot 4.0.8 + Spring AI 2.0.1 + Spring Cloud 2025.1.3 多模块示例。
- `weather-mcp-server` 当前配置为 `spring.ai.mcp.server.protocol: STATELESS`，端点 `/mcp`，端口 `8081`。
- 已确认 Spring AI 2.0.1 的 MCP Server **没有内置 MCP 级 OAuth/安全自动配置**（`spring-ai-autoconfigure-mcp-server-common` 与 `mcp-spring-webmvc` 模块中无 security/oauth 逻辑），因此需按官方 Spring 博客做法自行组合 Spring Security 的 OAuth2 授权服务器与资源服务器。
- 上一会话曾实现并随后删除了「用户上下文透传」实验（JWT 解析 → Feign 拦截器 → weather-service 日志），但 **测试、README、jjwt 依赖与 weather-service 日志过滤器残留**，其中 mcp-server 的 3 个 `usercontext` 测试引用了已删除的 main 类，**当前会导致 `mvn test-compile` 失败**（已实测确认）。

## 范围

### 包含

- 内嵌 Spring Authorization Server（OAuth 2.1）签发 JWT。
- 资源服务器校验 Bearer JWT，保护 `/mcp`。
- 两个 OAuth 客户端：`authorization_code + PKCE`（公钥客户端）与 `client_credentials`（机密客户端）。
- OAuth `scope` 工具级授权（`weather:read`）。
- MCP 规范要求的 OAuth 发现（RFC 9728 Protected Resource Metadata：`WWW-Authenticate` 挑战携带 `resource_metadata`/`scope`，并提供 `/.well-known/oauth-protected-resource`）。
- 自动化测试与本地手工验收说明。
- 清理「用户上下文透传」实验的过期产物（见「清理范围」）。

### 不包含

- 外部身份提供商（IdP，如 Keycloak/Okta）。本次为内嵌授权服务器方案；未来迁到外部 IdP 属演进，不在本次交付。
- MCP Client 端 / AI Agent。
- 用户上下文透传功能本身（作为独立实验移除，不恢复）。
- TLS/HTTPS（生产必配，本 demo 仅本地演示）。
- WebFlux/MCP SSE 支持（本项目为 WebMVC + Streamable HTTP）。
- 多工具（当前仅 `get_weather_by_city`）。

## 技术选型

| 项目 | 选择 |
| --- | --- |
| 授权方案 | MCP 规范的 OAuth 2.1 |
| 部署模式 | 模式 A：内嵌授权服务器（与资源服务器同进程） |
| 授权服务器 | Spring Authorization Server（`spring-boot-starter-oauth2-authorization-server`） |
| 资源服务器 | Spring Security Resource Server（`spring-boot-starter-oauth2-resource-server`） |
| Token 类型 | JWT（有状态签名，RS256，JWKS 供校验） |
| 授权流程 | `authorization_code + PKCE` 与 `client_credentials` |
| 工具级授权 | 方法级安全 `@PreAuthorize` + `SCOPE_weather:read` |
| 传输 | 保持 `STATELESS` streamable HTTP |

## 总体架构

```
MCP 客户端 (MCP Inspector / curl / Claude Desktop)
   │  ①  POST /mcp  无 token  →  401 + WWW-Authenticate: Bearer resource_metadata="...", scope="weather:read"
   │  ②  GET /.well-known/oauth-protected-resource
   │        → {"resource":"http://localhost:8081/mcp","authorization_servers":["http://localhost:8081"]}
   │  ③  发现授权服务器（/.well-known/oauth-authorization-server）
   │      → 走授权码+PKCE 或 client_credentials 换取 access_token
   ▼
weather-mcp-server:8081   （单进程，三合一）
   ├─ OAuth2 Authorization Server
   │     /oauth2/authorize、/oauth2/token、/oauth2/jwks、/.well-known/oauth-authorization-server
   ├─ OAuth2 Resource Server
   │     校验 Bearer JWT（issuer / JWKS 自指本机），通过后建立 Authentication
   └─ MCP 工具端点 /mcp（STATELESS）
         Spring AI @Tool + @PreAuthorize("hasAuthority('SCOPE_weather:read')")
```

授权服务器与资源服务器逻辑上是两个角色，物理上部署在同一进程（符合 MCP 规范「may be hosted with the resource server or a separate entity」）。未来若迁至模式 B，仅需移除内嵌 AS、改用外部 IdP 的 `issuer-uri`/JWKS 即可。

## 依赖变更（`weather-mcp-server/pom.xml`）

新增：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-authorization-server</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-test</artifactId>
  <scope>test</scope>
</dependency>
```

移除：`io.jsonwebtoken:jjwt-*`（jjwt-api/jjwt-impl/jjwt-jackson）。它们仅被已删除的 `JwtTokenParser` 使用，OAuth 校验改由 Spring Security 的 Nimbus JWT 处理器承担，不再需要 jjwt。

## 授权服务器与客户端注册（`application.yml`）

```yaml
spring:
  security:
    oauth2:
      authorizationserver:
        client:
          weather-mcp-public:                 # 授权码 + PKCE（公钥客户端，无 secret）
            registration:
              client-id: weather-mcp-public
              client-name: Weather MCP Public Client
              client-authentication-methods: none
              authorization-grant-types: authorization_code, refresh_token
              redirect-uris:
                - http://127.0.0.1:5173/callback   # 供 MCP Inspector 等本地客户端使用，可按客户端调整
              scopes: [weather:read]
              require-authorization-consent: false  # 演示简化：不展示同意页
          weather-mcp-machine:                # client_credentials（机器对机器）
            registration:
              client-id: weather-mcp-machine
              client-secret: "{noop}demo-secret"
              client-authentication-methods: client_secret_basic
              authorization-grant-types: client_credentials
              scopes: [weather:read]
```

> `client-secret` 使用 `{noop}` 仅为本地演示，需以 `spring.security.oauth2.authorizationserver.client.weather-mcp-machine.registration.client-secret` 环境变量覆盖；真实部署必须外置授权服务器并启用 TLS。`redirect-uris` 需匹配实际 MCP 客户端回调地址。

## 安全配置（新增 `config/SecurityConfig.java`）

- `@EnableWebSecurity` + `@EnableMethodSecurity`。
- `SecurityFilterChain`：
  - `.with(authorizationServer(), Customizer.withDefaults())` 启用内嵌 AS。
  - 授权规则：`/mcp` 需认证（`authenticated()`）；其余（`/actuator/health`、`/.well-known/oauth-protected-resource`、OAuth 端点、`/oauth2/jwks` 等）放行（`permitAll()`）。
  - `.oauth2ResourceServer(rs -> rs.authenticationEntryPoint(mcpBearerAuthenticationEntryPoint()).jwt(Customizer.withDefaults()))`。
- 资源服务器 JWT 解码：配置 `spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081`，并通过 `AuthorizationServerSettings` 将 AS issuer 显式设为 `http://localhost:8081`，保证两者匹配。

### MCP 规范发现（RFC 9728 Protected Resource Metadata）

默认 Spring Security 的 401 挑战不含 `resource_metadata`。为满足 MCP 规范，需额外实现：

1. **自定义 `BearerTokenAuthenticationEntryPoint`**：无/非法 token 访问 `/mcp` 时返回 `401`，并在 `WWW-Authenticate` 中加入
   `Bearer resource_metadata="http://localhost:8081/.well-known/oauth-protected-resource", scope="weather:read"`。
2. **新增 `web/ProtectedResourceMetadataController`**：暴露 `GET /.well-known/oauth-protected-resource`，返回
   ```json
   { "resource": "http://localhost:8081/mcp", "authorization_servers": ["http://localhost:8081"] }
   ```

> 这样 MCP Inspector / Claude Desktop 等真实客户端能自动发现授权服务器并完成 OAuth，而不仅是依赖 curl 手动拿 token。

## 工具级授权

`WeatherTool.getWeatherByCity` 增加方法级授权：

```java
@PreAuthorize("hasAuthority('SCOPE_weather:read')")
@Tool(name = "get_weather_by_city", description = "查询指定城市的当前天气")
public WeatherToolResult getWeatherByCity(@ToolParam(...) String city) { ... }
```

Spring Security 将 JWT `scope` claim 映射为 `SCOPE_<scope>` 权限。两个客户端均已授予 `weather:read`，缺失或错误 scope 的 token 返回 `403`。

## 清理范围（移除「用户上下文透传」实验的过期产物）

上一会话该实验的 main 类已被删除，但以下产物保留且在 mcp-server 侧**直接破坏构建**。本次一并移除：

| 位置 | 文件 | 处置 |
| --- | --- | --- |
| `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/` | `JwtTokenParserTest.java`、`UserContextExtractionFilterTest.java`、`UserContextFeignInterceptorTest.java` | **删除**（引用已删除的 main 类 `JwtTokenParser`/`UserContextHolder`/`UserContextExtractionFilter`/`UserContextFeignInterceptor`/`UserContext`，当前编译失败） |
| `weather-mcp-server/pom.xml` | jjwt 三个依赖 | 移除 |
| `weather-service/src/main/java/com/example/weather/service/web/` | `UserContextLoggingFilter.java` | 删除（`@Component`，仅打印 `X-User-Id`/`X-User-Tenant`；MCP 侧拦截器已删，无任何请求再携带这些头） |
| `weather-service/src/test/java/com/example/weather/service/web/` | `UserContextLoggingFilterTest.java` | 删除（仅测试上述过滤器） |
| `README.md` | 「用户上下文透传实验」整节（两种方式、JWT 生成脚本、`USER_CONTEXT_SOURCE`/`USER_CONTEXT_JWT_SECRET` 说明）与「本 demo 不做 MCP 认证」提示 | 移除，改由 OAuth 2.1 说明替代 |

清理后 `weather-mcp-server` 不再依赖 jjwt，`weather-service` 不再保留用户上下文日志过滤器，README 不再描述已移除的功能。

## 配置摘要（`weather-mcp-server/src/main/resources/application.yml` 增补/修改）

保留现有 `spring.ai.mcp.server.protocol: STATELESS`、`name`、`version`、`streamable-http.mcp-endpoint: /mcp`。新增：

```yaml
spring:
  security:
    oauth2:
      authorizationserver:
        client:
          weather-mcp-public:
            registration:
              client-id: weather-mcp-public
              client-name: Weather MCP Public Client
              client-authentication-methods: none
              authorization-grant-types: authorization_code, refresh_token
              redirect-uris:
                - http://127.0.0.1:5173/callback
              scopes: [weather:read]
              require-authorization-consent: false
          weather-mcp-machine:
            registration:
              client-id: weather-mcp-machine
              client-secret: "{noop}demo-secret"
              client-authentication-methods: client_secret_basic
              authorization-grant-types: client_credentials
              scopes: [weather:read]
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8081
```

其中 `weather-mcp-machine` 相关值建议通过环境变量注入，避免把固定 secret 写死在配置文件（见「文档」安全提醒）。

## 测试策略

### 自动化测试（`weather-mcp-server`）

沿用 test profile 禁 Eureka；使用内嵌 AS + `MockMvc`，无外部基础设施。

- 未带 token `POST /mcp` → `401`，且 `WWW-Authenticate` 含 `resource_metadata` 与 `scope="weather:read"`。
- 带无效/过期 token → `401`。
- `client_credentials` 调 `POST /oauth2/token` → `200`，返回 `access_token`、`scope`、`token_type=Bearer`。
- 带 `weather:read` token 调 `POST /mcp`（tools/call）→ 成功（可结合现有 WebMvc 测试或 `@SpringBootTest`）。
- 带无 `weather:read` scope（或错误 scope）的 token → `403`。
- `GET /.well-known/oauth-protected-resource` → 返回含 `authorization_servers` 的 JSON。
- 原 `WeatherToolTest`、`WeatherToolConfigurationTest`、`WeatherMcpServerApplicationTest` 保持通过（必要时补充认证上下文）。

### weather-service 测试

清理后 `UserContextLoggingFilterTest` 删除，其余现有测试不受影响并保持通过。

### 全量构建

`mvn -Dmaven.repo.local=.m2-repo verify` 成功（`weather-mcp-server` 与 `weather-service` 均绿）。

### 手工验收

1. `client_credentials` 取 token：
   ```bash
   curl -s -XPOST http://localhost:8081/oauth2/token \
     --user weather-mcp-machine:demo-secret \
     -d grant_type=client_credentials -d scope=weather:read
   ```
   得到 `access_token`。
2. 用该 token 调工具（先按文档初始化 MCP 会话后调用）：
   ```bash
   curl -s -X POST http://localhost:8081/mcp \
     -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -H "Authorization: Bearer $ACCESS_TOKEN" \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_weather_by_city","arguments":{"city":"北京"}}}'
   ```
   返回结构化天气。
3. 不带 token 重复调用 → `401` + `WWW-Authenticate` 挑战头。
4. 使用 MCP Inspector（Streamable HTTP，`http://localhost:8081/mcp`）走授权码 + PKCE 全程，验证自动发现授权服务器并完成 OAuth。
5. 生产提醒：真实部署需启用 TLS，并用环境变量覆盖 `client-secret`（或换用外部授权服务器）。

## 文档更新

- `README.md`：删除用户上下文实验与「不做 MCP 认证」说明；新增「OAuth 2.1 认证」章节，含配置、两个流程的验证步骤、安全提醒（TLS、外置 IdP、secret 外置）。
- 新增本设计文档并提交。

## 验收标准

- `mvn -Dmaven.repo.local=.m2-repo verify` 全绿（清理后恢复，重新引入 OAuth 测试后仍绿）。
- `weather-mcp-server` 可独立启动，OAuth 授权服务器与资源服务器同进程工作。
- `POST /mcp` 未认证返回 `401` 且带 `resource_metadata`/`scope` 挑战头。
- `client_credentials` 与 `authorization_code + PKCE` 均可换取可用 token。
- 带 `weather:read` token 可调用工具；无该 scope 或非法 token 被拒（`403`/`401`）。
- `GET /.well-known/oauth-protected-resource` 返回 `authorization_servers`。
- 已删除所有「用户上下文透传」过期产物（3 个 mcp-server 测试、jjwt 依赖、weather-service 日志过滤器及其测试、README 相关章节）。
