# Weather MCP Server OAuth 2.1 认证 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `weather-mcp-server` 增加符合 MCP 规范的 OAuth 2.1 认证（内嵌授权服务器 + 资源服务器、授权码+PKCE 与 client_credentials 两种流程、`weather:read` scope 工具级授权、RFC 9728 发现），并清理上一会话遗留的「用户上下文透传」过期产物。

**Architecture:** 单进程三合一。`spring-boot-starter-oauth2-authorization-server` 签发 JWT（RS256，授权码+PKCE、client_credentials），`spring-boot-starter-oauth2-resource-server` 校验 Bearer JWT 保护 `/mcp`。`SecurityFilterChain` 通过 `.with(authorizationServer(), ...)` + `.oauth2ResourceServer(...)` 同时启用两个角色；`/mcp` 要求 `SCOPE_weather:read`。资源服务器的 `JwtDecoder` 复用授权服务器的 `JWKSource`（`OAuth2AuthorizationServerConfiguration.jwtDecoder(...)`，进程内校验、无 HTTP 发现），便于测试且避免测试环境无法访问 `issuer-uri` 的问题。自定义入口点让 401 挑战携带 `resource_metadata`/`scope`，并新增 `/.well-known/oauth-protected-resource` 端点。传输保持 `STATELESS`。

**Tech Stack:** Spring Boot 4.0.8、Spring AI 2.0.1、Spring Cloud 2025.1.3、Spring Security 6.x（Authorization Server + Resource Server）、Nimbus JOSE、JUnit 5 + AssertJ + Mockito + MockMvc。

## Global Constraints

- 多模块 Maven；构建命令使用工作区本地仓库：`mvn -o -Dmaven.repo.local=.m2-repo ...`。
- 协议保持 `STATELESS`（`spring.ai.mcp.server.protocol: STATELESS`），不切回 `STREAMABLE`。
- MCP 端点 `/mcp`；端口 `8081`；应用名 `weather-mcp-server`。
- OAuth scope 固定为 `weather:read`；客户端 id 为 `weather-mcp-public`（授权码+PKCE，公钥客户端）与 `weather-mcp-machine`（client_credentials）。
- 授权服务器 issuer 固定 `http://localhost:8081`（`AuthorizationServerSettings`）。
- 资源服务器 `JwtDecoder` 用授权服务器的 `JWKSource`（进程内校验）；**不使用** `spring.security.oauth2.resourceserver.jwt.issuer-uri`（避免测试环境 HTTP 发现失败）。
- `@EnableWebSecurity` + `@EnableMethodSecurity` 均要启用；方法级 `@PreAuthorize("hasAuthority('SCOPE_weather:read')")` 作为纵深防御，HTTP 层 `/mcp` 用 `hasAuthority("SCOPE_weather:read")` 做主要授权。
- 测试一律不依赖外部基础设施（test profile 禁 Eureka）；`client-secret` 用 `{noop}demo-secret` 仅为本地演示。
- 命名/文案沿用现有中文风格。

---

## 文件结构总览

**修改：**
- `weather-mcp-server/pom.xml` — 移除 jjwt 三依赖；新增 security、oauth2-resource-server、oauth2-authorization-server、spring-security-test。
- `weather-mcp-server/src/main/resources/application.yml` — 新增 OAuth AS 客户端注册（不设置 `resourceserver.jwt.issuer-uri`）。
- `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherTool.java` — `getWeatherByCity` 加 `@PreAuthorize`。
- `weather-mcp-server/src/test/java/com/example/weather/mcp/WeatherMcpServerApplicationTest.java` — 协议断言改为 `STATELESS`。
- `weather-service/src/main/java/com/example/weather/service/web/UserContextLoggingFilter.java` — 删除。

**删除：**
- `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/`（`JwtTokenParserTest`、`UserContextExtractionFilterTest`、`UserContextFeignInterceptorTest`）。
- `weather-service/src/test/java/com/example/weather/service/web/UserContextLoggingFilterTest.java`。
- `README.md` 中的「用户上下文透传实验」整节与「本 demo 不做 MCP 认证」提示。

**新增（main）：**
- `weather-mcp-server/src/main/java/com/example/weather/mcp/config/SecurityConfig.java`
- `weather-mcp-server/src/main/java/com/example/weather/mcp/config/McpBearerAuthenticationEntryPoint.java`
- `weather-mcp-server/src/main/java/com/example/weather/mcp/web/ProtectedResourceMetadataController.java`

**新增（test）：**
- `weather-mcp-server/src/test/java/com/example/weather/mcp/config/McpSecurityFilterTest.java`
- `weather-mcp-server/src/test/java/com/example/weather/mcp/config/OAuth2TokenEndpointTest.java`
- `weather-mcp-server/src/test/java/com/example/weather/mcp/web/ProtectedResourceMetadataControllerTest.java`

---

## Task 1: 清理「用户上下文透传」过期产物，恢复绿色构建

**Files:**
- Delete: `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/JwtTokenParserTest.java`, `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextExtractionFilterTest.java`, `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextFeignInterceptorTest.java`
- Delete: `weather-service/src/main/java/com/example/weather/service/web/UserContextLoggingFilter.java`, `weather-service/src/test/java/com/example/weather/service/web/UserContextLoggingFilterTest.java`
- Modify: `weather-mcp-server/pom.xml`（移除 jjwt 三依赖）
- Modify: `weather-mcp-server/src/test/java/com/example/weather/mcp/WeatherMcpServerApplicationTest.java`
- Modify: `README.md`（删除用户上下文实验整节与「不做 MCP 认证」说明）

**Interfaces:**
- Consumes: 无。
- Produces: 无新增接口；恢复 `mvn verify` 全绿，为后续任务提供可构建基线。

- [ ] **Step 1: 删除 3 个 mcp-server usercontext 测试文件**

```bash
rm weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/JwtTokenParserTest.java \
   weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextExtractionFilterTest.java \
   weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextFeignInterceptorTest.java
```

- [ ] **Step 2: 删除 weather-service 日志过滤器及其测试**

```bash
rm weather-service/src/main/java/com/example/weather/service/web/UserContextLoggingFilter.java \
   weather-service/src/test/java/com/example/weather/service/web/UserContextLoggingFilterTest.java
```

- [ ] **Step 3: 从 `weather-mcp-server/pom.xml` 移除 jjwt 三依赖**

删除 `io.jsonwebtoken:jjwt-api`、`jjwt-impl`、`jjwt-jackson` 三个 `<dependency>` 块（参考原文件第 34-50 行）。

- [ ] **Step 4: 修正 `WeatherMcpServerApplicationTest` 协议断言为 STATELESS**

把断言 `isEqualTo("STREAMABLE")` 改为 `isEqualTo("STATELESS")`：

```java
assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STATELESS");
```

- [ ] **Step 5: 删除 README 中过期的用户上下文章节**

删除 `README.md` 从 `## 用户上下文透传实验` 到该节结束（含两种方式、JWT 生成脚本、`USER_CONTEXT_SOURCE`/`USER_CONTEXT_JWT_SECRET` 说明），并把「> 说明：token 缺失、无效或验签失败时请求不会被拒绝（本 demo 不做 MCP 认证）...」一并删除。

- [ ] **Step 6: 运行完整构建确认全绿**

```bash
mvn -o -Dmaven.repo.local=.m2-repo test
```
Expected: BUILD SUCCESS，`weather-mcp-server` 与 `weather-service` 均通过。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor: remove orphaned user-context propagation artifacts"
```

---

## Task 2: 新增 OAuth2 依赖、授权服务器注册与安全过滤链

**Files:**
- Modify: `weather-mcp-server/pom.xml`（新增 4 个依赖）
- Modify: `weather-mcp-server/src/main/resources/application.yml`（新增 AS 客户端注册）
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/config/McpBearerAuthenticationEntryPoint.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/config/SecurityConfig.java`
- Create: `weather-mcp-server/src/test/java/com/example/weather/mcp/config/OAuth2TokenEndpointTest.java`
- Create: `weather-mcp-server/src/test/java/com/example/weather/mcp/config/McpSecurityFilterTest.java`

**Interfaces:**
- Consumes: 无（本任务建立核心安全配置）。
- Produces（供后序任务使用）:
  - 客户端 id：`weather-mcp-public`（授权码+PKCE，无 secret）、`weather-mcp-machine`（`client_secret_basic`，secret=`demo-secret`）。
  - scope：`weather:read`。
  - 守卫规则：`/mcp` 需要 `SCOPE_weather:read`；其余 `permitAll`。
  - 入口点：401 时 `WWW-Authenticate: Bearer resource_metadata="http://localhost:8081/.well-known/oauth-protected-resource", scope="weather:read"`。
  - AS issuer：`http://localhost:8081`（`AuthorizationServerSettings`）。

- [ ] **Step 1: `pom.xml` 新增依赖**

在 `<dependencies>` 中新增；`spring-security-test` 用 `test` scope：

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

- [ ] **Step 2: `application.yml` 新增 OAuth 客户端注册**

在 `spring:` 节点下新增（不设置 `resourceserver.jwt.issuer-uri`）：

```yaml
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
```

- [ ] **Step 3: 创建 `McpBearerAuthenticationEntryPoint`**

`weather-mcp-server/src/main/java/com/example/weather/mcp/config/McpBearerAuthenticationEntryPoint.java`：

```java
package com.example.weather.mcp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class McpBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String RESOURCE_METADATA_URL = "http://localhost:8081/.well-known/oauth-protected-resource";
    static final String REQUIRED_SCOPE = "weather:read";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                "Bearer resource_metadata=\"" + RESOURCE_METADATA_URL + "\", scope=\"" + REQUIRED_SCOPE + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
```

- [ ] **Step 4: 创建 `SecurityConfig`**

`weather-mcp-server/src/main/java/com/example/weather/mcp/config/SecurityConfig.java`：

```java
package com.example.weather.mcp.config;

import static org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer.authorizationServer;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .with(authorizationServer(), Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/mcp").hasAuthority("SCOPE_" + McpBearerAuthenticationEntryPoint.REQUIRED_SCOPE)
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(new McpBearerAuthenticationEntryPoint())
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer("http://localhost:8081").build();
    }
}
```

> 说明：`authorizationServer()` 为 `/oauth2/*`、`/.well-known/oauth-authorization-server`、`/oauth2/jwks` 创建独立安全链，故 `anyRequest().permitAll()` 无需为这些端点额外放行；`permitAll` 覆盖 `/actuator/**` 与 `/.well-known/oauth-protected-resource` 等。`jwtDecoder` 用授权服务器自己的 `JWKSource` 进程内校验（无需 HTTP），AS 签发的 token 可直接被 RS 验证。

- [ ] **Step 5: 先写 `OAuth2TokenEndpointTest` 验证 token 签发**

`weather-mcp-server/src/test/java/com/example/weather/mcp/config/OAuth2TokenEndpointTest.java`：

```java
package com.example.weather.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class OAuth2TokenEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void issuesAccessTokenViaClientCredentials() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("weather-mcp-machine", "demo-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("weather:read"));
    }

    @Test
    void rejectsInvalidClientCredentials() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("weather-mcp-machine", "wrong-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 6: 先写 `McpSecurityFilterTest` 验证 401/403/放行与真实 token 端到端**

`weather-mcp-server/src/test/java/com/example/weather/mcp/config/McpSecurityFilterTest.java`：

```java
package com.example.weather.mcp.config;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class McpSecurityFilterTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";

    @Test
    void unauthorizedWhenNoToken() throws Exception {
        MvcResult result = mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isUnauthorized())
                .andReturn();
        String challenge = result.getResponse().getHeader("WWW-Authenticate");
        assertThat(challenge).contains("resource_metadata=");
        assertThat(challenge).contains("scope=\"weather:read\"");
    }

    @Test
    @WithMockUser(authorities = "ROLE_OTHER")
    void forbiddenWithoutRequiredScope() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "SCOPE_weather:read")
    void allowedWithRequiredScope() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsAuthorizationServerIssuedToken() throws Exception {
        String token = obtainAccessToken();
        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isOk());
    }

    private String obtainAccessToken() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/token")
                        .with(httpBasic("weather-mcp-machine", "demo-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.get("access_token").asText();
    }
}
```

- [ ] **Step 7: 运行 `OAuth2TokenEndpointTest` 与 `McpSecurityFilterTest` 确认通过**

Run: `mvn -o -Dmaven.repo.local=.m2-repo -pl weather-mcp-server -Dtest=OAuth2TokenEndpointTest,McpSecurityFilterTest test`
Expected: BUILD SUCCESS，全部通过。

> 若 `allowedWithRequiredScope`/`acceptsAuthorizationServerIssuedToken` 因 STATELESS MCP 对 `initialize` 的响应非 200 而失败，把断言放宽为 `.andExpect(status().isNotUnauthorized())` + `.andExpect(status().isNotForbidden())` 以验证安全层放行，不要修改生产规则。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat: protect MCP endpoint with OAuth2 authorization+resource server"
```

---

## Task 3: 新增 RFC 9728 受保护资源元数据端点

**Files:**
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/web/ProtectedResourceMetadataController.java`
- Create: `weather-mcp-server/src/test/java/com/example/weather/mcp/web/ProtectedResourceMetadataControllerTest.java`

**Interfaces:**
- Consumes: 无（独立端点，`/mcp` 之外，`permitAll`）。
- Produces: `GET /.well-known/oauth-protected-resource` → `{"resource":"http://localhost:8081/mcp","authorization_servers":["http://localhost:8081"]}`。

- [ ] **Step 1: 创建 controller**

`weather-mcp-server/src/main/java/com/example/weather/mcp/web/ProtectedResourceMetadataController.java`：

```java
package com.example.weather.mcp.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedResourceMetadataController {

    static final String BASE_URL = "http://localhost:8081";

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", BASE_URL + "/mcp",
                "authorization_servers", List.of(BASE_URL));
    }
}
```

- [ ] **Step 2: 创建测试**

`weather-mcp-server/src/test/java/com/example/weather/mcp/web/ProtectedResourceMetadataControllerTest.java`：

```java
package com.example.weather.mcp.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ProtectedResourceMetadataControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void exposesProtectedResourceMetadata() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost:8081/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost:8081"));
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `mvn -o -Dmaven.repo.local=.m2-repo -pl weather-mcp-server -Dtest=ProtectedResourceMetadataControllerTest test`
Expected: PASS。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat: add OAuth2 protected resource metadata endpoint (RFC 9728)"
```

---

## Task 4: 工具级 scope 授权

**Files:**
- Modify: `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherTool.java`

**Interfaces:**
- Consumes: SecurityConfig 已为 `/mcp` 设置 `hasAuthority("SCOPE_weather:read")`。
- Produces: 工具方法要求 `SCOPE_weather:read`（纵深防御，与 HTTP 规则一致）。

- [ ] **Step 1: `WeatherTool.getWeatherByCity` 加 `@PreAuthorize`**

```java
import org.springframework.security.access.prepost.PreAuthorize;

@PreAuthorize("hasAuthority('SCOPE_weather:read')")
@Tool(name = "get_weather_by_city", description = "查询指定城市的当前天气")
public WeatherToolResult getWeatherByCity(
        @ToolParam(description = "城市名称，例如北京或 Beijing") String city) { ... }
```

- [ ] **Step 2: 运行现有工具测试确认通过**

Run: `mvn -o -Dmaven.repo.local=.m2-repo -pl weather-mcp-server -Dtest=WeatherToolTest,WeatherToolConfigurationTest test`
Expected: PASS（工具逻辑不受影响）。若 `WeatherToolConfigurationTest` 因新增安全依赖的上下文加载失败，检查是否有未处理的 `JwtDecoder` 配置冲突。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "feat: enforce weather:read scope on weather tool"
```

---

## Task 5: 更新 README 文档

**Files:**
- Modify: `README.md`

**Interfaces:** 无。

- [ ] **Step 1: 在 README 中新增「OAuth 2.1 认证」章节**

在 `## Connect an MCP Client` 之前新增；内容：

```markdown
## OAuth 2.1 认证（内嵌授权服务器）

`weather-mcp-server` 使用 MCP 规范的 OAuth 2.1 认证：内嵌 Spring Authorization Server 签发 JWT，
并对 `/mcp` 端点做资源服务器校验，要求 `scope=weather:read`。

- 授权端点：
  - Token：`POST http://localhost:8081/oauth2/token`
  - 授权：`GET http://localhost:8081/oauth2/authorize`
  - JWKS：`GET http://localhost:8081/oauth2/jwks`
  - 发现：`GET http://localhost:8081/.well-known/oauth-authorization-server`
- 受保护资源元数据：`GET http://localhost:8081/.well-known/oauth-protected-resource`

### 客户端

| 客户端 | 流程 | 配置 |
| --- | --- | --- |
| `weather-mcp-public` | 授权码 + PKCE（公钥客户端，无 secret） | `redirect-uri: http://127.0.0.1:5173/callback` |
| `weather-mcp-machine` | client_credentials | `weather-mcp-machine:demo-secret` |

### 用 client_credentials 取 token 并调用工具

```bash
ACCESS_TOKEN=$(curl -s -XPOST http://localhost:8081/oauth2/token \
  --user weather-mcp-machine:demo-secret \
  -d grant_type=client_credentials -d scope=weather:read | jq -r .access_token)

curl --silent --show-error \
  -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_weather_by_city","arguments":{"city":"北京"}}}'
```

不带 token 调用会返回 `401`，并带 `WWW-Authenticate: Bearer resource_metadata="...", scope="weather:read"` 挑战头。

### 用 MCP Inspector 走授权码 + PKCE

在 MCP Inspector 中选择 Streamable HTTP，填入 `http://localhost:8081/mcp`，跟随其内建 OAuth 流程
（使用的客户端为 `weather-mcp-public`）。首次会跳转本机授权页并回调到 `http://127.0.0.1:5173/callback`。

> 安全提醒：`client-secret`（`demo-secret`）与 `{noop}` 仅为本地演示。真实部署必须启用 TLS，
> 通过环境变量覆盖 `spring.security.oauth2.authorizationserver.client.weather-mcp-machine.registration.client-secret`，
> 或改用外部授权服务器（届时 MCP Server 仅保留资源服务器角色）。
```

- [ ] **Step 2: 更新 README 底部「Security Notice」**

把 `## Security Notice` 下的「This demo does not implement MCP authentication. Do not expose `/mcp` directly to the public internet. A production deployment must add TLS, authentication, authorization, and network access controls in the application or an upstream gateway.」改为：

```markdown
## Security Notice

This demo enables MCP OAuth 2.1 authentication on `/mcp` (see above) but does **not**
enable TLS in the demo profile. Do not expose `/mcp` directly to the public internet
without TLS. A production deployment must enable TLS, externalize the OAuth client
secret (or replace the embedded authorization server with an external IdP), and add
network access controls in the application or an upstream gateway.
```

- [ ] **Step 3: 检查 README 无残留「不做 MCP 认证」表述**

```bash
grep -n "不做 MCP 认证" README.md || echo "clean"
```

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs: add OAuth 2.1 authentication guide"
```

---

## Task 6: 全量验证

**Files:** 无新增。

**Interfaces:** 无。

- [ ] **Step 1: 运行完整构建**

```bash
mvn -o -Dmaven.repo.local=.m2-repo verify
```
Expected: BUILD SUCCESS，两模块全绿（含新 OAuth 测试）。

- [ ] **Step 2: 手工验收（可选，需 Eureka 与两个服务）**

按 README「OAuth 2.1 认证」章节执行 client_credentials 取 token 并调用 `get_weather_by_city`；再用 MCP Inspector 走授权码+PKCE；未认证调用验证 `401` 挑战头。

- [ ] **Step 3: 确认工作区干净**

```bash
git status
```
Expected: 无未提交改动。

---

## 自审

**Spec 覆盖核对：**
- 内嵌 OAuth 2.1 授权服务器 → Task 2。
- 资源服务器校验保护 `/mcp` → Task 2（`oauth2ResourceServer` + `JwtDecoder`）。
- 授权码+PKCE 与 client_credentials → Task 2 配置 + token 测试。
- `weather:read` scope 工具级授权 → Task 2 `hasAuthority` + Task 4 `@PreAuthorize`。
- RFC 9728 发现（挑战头 + 元数据端点）→ Task 2 入口点 + Task 3 端点。
- 清理 user-context 过期产物 → Task 1。
- 文档更新 → Task 5。
- STATELESS 保持 → Task 1 修正测试断言 + Task 2 配置（未改协议）。

**占位符扫描：** 无 TBD/TODO；所有代码步骤含实际内容。

**类型/签名一致性：** `McpBearerAuthenticationEntryPoint.REQUIRED_SCOPE`/`RESOURCE_METADATA_URL` 在 SecurityConfig 与入口点间一致；`jwtDecoder(JWKSource<SecurityContext>)` 依赖 AS 提供的 `JWKSource` bean；客户端 id/scope 在配置、测试、README 中一致；`SecurityMockMvcRequestPostProcessors.user(...)` 与 `httpBasic(...)` 导入路径正确。

**关键注意：** 资源服务器不使用 `issuer-uri`（避免测试环境 HTTP 发现失败），改用 `OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)` 进程内校验；AS 签发的 token 可直接被 RS 验证。`allowedWithRequiredScope`/`acceptsAuthorizationServerIssuedToken` 若因 MCP `initialize` 响应形态失败，按 Task 2 Step 7 的 note 放宽为「非 401/403」。
