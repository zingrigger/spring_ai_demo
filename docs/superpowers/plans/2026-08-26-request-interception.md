# 用户上下文请求拦截与 Feign 透传 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `weather-mcp-server` 实现两种可切换的入站请求拦截（Bearer JWT 解析 / 显式 `X-User-Id`、`X-User-Tenant` 头），通过 Feign 拦截器透传给 `weather-service`，由 `weather-service` 打印验证。

**Architecture:** 入站侧一个 `OncePerRequestFilter` 按配置 `user-context.source` 提取用户上下文写入 `UserContextHolder`（ThreadLocal）；出站侧一个 Feign `RequestInterceptor` 从 Holder 取出并附加 `X-User-Id`/`X-User-Tenant`。Feign 调用与过滤器同线程（WebMVC 同步执行），ThreadLocal 可靠。下游 `weather-service` 用过滤器打印收到的 header。设计详见 `docs/superpowers/specs/2026-08-26-request-interception-design.md`。

**Tech Stack:** Java 21、Spring Boot 4.0.8、Spring Cloud 2025.1.3、Spring AI 2.0.1、jjwt 0.12.6（HS256）、JUnit 5 + AssertJ、Mockito、spring-test MockHttpServletRequest。

## Global Constraints

- 所有新代码位于各自模块现有包结构内；MCP server 新类放 `com.example.weather.mcp.usercontext`，weather-service 新类放 `com.example.weather.service.web`。
- jjwt 固定 `0.12.6`（`jjwt-api` 编译期；`jjwt-impl`、`jjwt-jackson` runtime scope）。
- 下游 header 名统一为 `X-User-Id` / `X-User-Tenant`；`Authorization` 头不透传下游。
- 不做鉴权强制：token 缺失/无效不拒绝请求（不返回 401），只记录日志。
- 配置项：`user-context.source`（`bearer-token` | `explicit-headers`，默认 `bearer-token`）、`user-context.jwt.secret`（HS256 密钥 ≥32 字节）。
- 每个任务的测试必须在 `mvn verify` 下通过；测试 profile 继续禁用 Eureka。
- 提交信息用现有风格（`feat:` / `test:` / `docs:` + 中文或英文描述）。

---

### Task 1: 用户上下文模型与 JWT 解析（weather-mcp-server）

**Files:**
- Modify: `weather-mcp-server/pom.xml`（jjwt 依赖，第 38 行 `</dependencies>` 前）
- Modify: `weather-mcp-server/src/main/resources/application.yml`（末尾追加 `user-context` 配置）
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/UserContext.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/UserContextHolder.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/JwtTokenParser.java`
- Test: `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/JwtTokenParserTest.java`

**Interfaces:**
- Consumes: 无（Task 1 是基础）
- Produces:
  - `UserContext(String userId, String tenantId)` — record，两字段均可为 null
  - `UserContextHolder.set(UserContext)` / `UserContextHolder.get() -> UserContext`（无则 null）/ `UserContextHolder.clear()`
  - `JwtTokenParser.parse(String token) -> Optional<UserContext>`；构造函数 `JwtTokenParser(@Value("${user-context.jwt.secret}") String secret)`

- [ ] **Step 1: 添加 jjwt 依赖**

在 `weather-mcp-server/pom.xml` 的 `<dependencies>` 内、`spring-boot-starter-test` 之前加入：

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 添加 user-context 配置**

在 `weather-mcp-server/src/main/resources/application.yml` 末尾追加：

```yaml
user-context:
  source: ${USER_CONTEXT_SOURCE:bearer-token}   # bearer-token | explicit-headers
  jwt:
    secret: ${USER_CONTEXT_JWT_SECRET:demo-secret-change-me-0123456789abcdef}  # HS256 需 >=32 字节
```

- [ ] **Step 3: 写失败测试 `JwtTokenParserTest`**

```java
package com.example.weather.mcp.usercontext;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenParserTest {

    private static final String SECRET = "demo-secret-change-me-0123456789abcdef";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtTokenParser parser = new JwtTokenParser(SECRET);

    private static String tokenWithClaims(Instant expiration) {
        return Jwts.builder()
                .claim("userId", "1001")
                .claim("tenantId", "acme")
                .expiration(Date.from(expiration))
                .signWith(KEY)
                .compact();
    }

    @Test
    void parsesUserIdAndTenantIdFromValidToken() {
        Optional<UserContext> context = parser.parse(tokenWithClaims(Instant.now().plusSeconds(3600)));
        assertThat(context).hasValue(new UserContext("1001", "acme"));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-another-secret-0123456789".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder().claim("userId", "1001").signWith(otherKey).compact();
        assertThat(parser.parse(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        assertThat(parser.parse(tokenWithClaims(Instant.now().minusSeconds(60)))).isEmpty();
    }

    @Test
    void returnsEmptyForMalformedToken() {
        assertThat(parser.parse("not-a-jwt")).isEmpty();
    }

    @Test
    void allowsMissingClaims() {
        String token = Jwts.builder().signWith(KEY).compact();
        assertThat(parser.parse(token)).hasValue(new UserContext(null, null));
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `mvn -pl weather-mcp-server test`
Expected: 编译失败，`JwtTokenParser` 不存在（依赖下载 jjwt 0.12.6 属正常现象）。

- [ ] **Step 5: 实现 `UserContext`**

```java
package com.example.weather.mcp.usercontext;

public record UserContext(String userId, String tenantId) {
}
```

- [ ] **Step 6: 实现 `UserContextHolder`**

```java
package com.example.weather.mcp.usercontext;

import org.springframework.util.Assert;

public final class UserContextHolder {

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext context) {
        Assert.notNull(context, "context must not be null");
        CONTEXT.set(context);
    }

    public static UserContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
```

- [ ] **Step 7: 实现 `JwtTokenParser`**

```java
package com.example.weather.mcp.usercontext;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public final class JwtTokenParser {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenParser.class);

    private final SecretKey secretKey;

    public JwtTokenParser(@Value("${user-context.jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<UserContext> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(secretKey).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new UserContext(
                    claims.get("userId", String.class),
                    claims.get("tenantId", String.class)));
        } catch (JwtException | IllegalArgumentException error) {
            logger.warn("JWT 解析失败: {}", error.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

Run: `mvn -pl weather-mcp-server test`
Expected: `JwtTokenParserTest` 5 个用例全部 PASS，其他既有测试不受影响。

- [ ] **Step 9: 提交**

```bash
git add weather-mcp-server/pom.xml weather-mcp-server/src/main/resources/application.yml \
  weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext \
  weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext
git commit -m "feat: add user context model and JWT parser"
```

---

### Task 2: 入站提取过滤器（weather-mcp-server）

**Files:**
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/UserContextExtractionFilter.java`
- Test: `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextExtractionFilterTest.java`

**Interfaces:**
- Consumes: `UserContext`、`UserContextHolder`、`JwtTokenParser.parse(String) -> Optional<UserContext>`（均来自 Task 1）
- Produces: `UserContextExtractionFilter` — `@Component`，构造 `(@Value("${user-context.source}") String source, JwtTokenParser)`，继承 `OncePerRequestFilter`；对全部请求生效；`finally` 清理 Holder

- [ ] **Step 1: 写失败测试 `UserContextExtractionFilterTest`**

```java
package com.example.weather.mcp.usercontext;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserContextExtractionFilterTest {

    private static final String SECRET = "demo-secret-change-me-0123456789abcdef";

    private final FilterChain filterChain = mock(FilterChain.class);
    private JwtTokenParser tokenParser;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        tokenParser = new JwtTokenParser(SECRET);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private static String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("userId", "1001")
                .claim("tenantId", "acme")
                .signWith(key)
                .compact();
    }

    @Test
    void bearerModeExtractsClaimsIntoHolder() throws Exception {
        request.addHeader("Authorization", "Bearer " + validToken());
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isEqualTo(new UserContext("1001", "acme"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void bearerModeLeavesHolderEmptyWithoutAuthorizationHeader() throws Exception {
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
    }

    @Test
    void explicitModeReadsHeadersIntoHolder() throws Exception {
        request.addHeader("X-User-Id", "1001");
        request.addHeader("X-User-Tenant", "acme");
        new UserContextExtractionFilter("explicit-headers", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isEqualTo(new UserContext("1001", "acme"));
    }

    @Test
    void explicitModeLeavesHolderEmptyWithoutHeaders() throws Exception {
        new UserContextExtractionFilter("explicit-headers", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
    }

    @Test
    void clearsHolderAfterRequest() throws Exception {
        request.addHeader("Authorization", "Bearer " + validToken());
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl weather-mcp-server test`
Expected: 编译失败，`UserContextExtractionFilter` 不存在。

- [ ] **Step 3: 实现 `UserContextExtractionFilter`**

```java
package com.example.weather.mcp.usercontext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class UserContextExtractionFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(UserContextExtractionFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_TENANT = "X-User-Tenant";

    private final String source;
    private final JwtTokenParser jwtTokenParser;

    public UserContextExtractionFilter(@Value("${user-context.source}") String source,
                                       JwtTokenParser jwtTokenParser) {
        this.source = source;
        this.jwtTokenParser = jwtTokenParser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if ("bearer-token".equals(source)) {
                extractFromBearerToken(request);
            } else {
                extractFromExplicitHeaders(request);
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private void extractFromBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            logger.debug("请求缺少 Authorization Bearer 头，跳过用户上下文提取");
            return;
        }
        jwtTokenParser.parse(authorization.substring(BEARER_PREFIX.length()))
                .ifPresent(UserContextHolder::set);
    }

    private void extractFromExplicitHeaders(HttpServletRequest request) {
        String userId = request.getHeader(X_USER_ID);
        String tenantId = request.getHeader(X_USER_TENANT);
        if (userId == null && tenantId == null) {
            logger.debug("请求缺少 X-User-Id/X-User-Tenant 头，跳过用户上下文提取");
            return;
        }
        UserContextHolder.set(new UserContext(userId, tenantId));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl weather-mcp-server test`
Expected: `UserContextExtractionFilterTest` 5 个用例全部 PASS，既有测试不受影响（`WeatherMcpServerApplicationTest` 会加载 `user-context` 配置，Step 1 的 Task 1 已提供）。

- [ ] **Step 5: 提交**

```bash
git add weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/UserContextExtractionFilter.java \
  weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextExtractionFilterTest.java
git commit -m "feat: add user context extraction filter"
```

---

### Task 3: Feign 出站拦截器（weather-mcp-server）

**Files:**
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/UserContextFeignInterceptor.java`
- Test: `weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextFeignInterceptorTest.java`

**Interfaces:**
- Consumes: `UserContextHolder.get()`（Task 1）
- Produces: `UserContextFeignInterceptor` — `@Component`，实现 `feign.RequestInterceptor`；Spring Cloud OpenFeign 自动把该 bean 应用到所有 Feign 客户端

- [ ] **Step 1: 写失败测试 `UserContextFeignInterceptorTest`**

```java
package com.example.weather.mcp.usercontext;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextFeignInterceptorTest {

    private final UserContextFeignInterceptor interceptor = new UserContextFeignInterceptor();

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void addsHeadersWhenContextPresent() {
        UserContextHolder.set(new UserContext("1001", "acme"));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers().get("X-User-Id")).containsExactly("1001");
        assertThat(template.headers().get("X-User-Tenant")).containsExactly("acme");
    }

    @Test
    void skipsNullValues() {
        UserContextHolder.set(new UserContext("1001", null));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers().get("X-User-Id")).containsExactly("1001");
        assertThat(template.headers()).doesNotContainKey("X-User-Tenant");
    }

    @Test
    void addsNoHeadersWithoutContext() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKeys("X-User-Id", "X-User-Tenant");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl weather-mcp-server test`
Expected: 编译失败，`UserContextFeignInterceptor` 不存在。

- [ ] **Step 3: 实现 `UserContextFeignInterceptor`**

```java
package com.example.weather.mcp.usercontext;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

@Component
public final class UserContextFeignInterceptor implements RequestInterceptor {

    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_TENANT = "X-User-Tenant";

    @Override
    public void apply(RequestTemplate template) {
        UserContext context = UserContextHolder.get();
        if (context == null) {
            return;
        }
        if (context.userId() != null) {
            template.header(X_USER_ID, context.userId());
        }
        if (context.tenantId() != null) {
            template.header(X_USER_TENANT, context.tenantId());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl weather-mcp-server test`
Expected: `UserContextFeignInterceptorTest` 3 个用例全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add weather-mcp-server/src/main/java/com/example/weather/mcp/usercontext/UserContextFeignInterceptor.java \
  weather-mcp-server/src/test/java/com/example/weather/mcp/usercontext/UserContextFeignInterceptorTest.java
git commit -m "feat: add feign interceptor propagating user context"
```

---

### Task 4: 下游打印过滤器（weather-service）

**Files:**
- Create: `weather-service/src/main/java/com/example/weather/service/web/UserContextLoggingFilter.java`
- Test: `weather-service/src/test/java/com/example/weather/service/web/UserContextLoggingFilterTest.java`

**Interfaces:**
- Consumes: 无（独立任务，不依赖 MCP server 的类）
- Produces: `UserContextLoggingFilter` — `@Component`，继承 `OncePerRequestFilter`；收到非空 `X-User-Id`/`X-User-Tenant` 时 INFO 日志

- [ ] **Step 1: 写失败测试 `UserContextLoggingFilterTest`**（用 logback `ListAppender` 捕获日志）

```java
package com.example.weather.service.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserContextLoggingFilterTest {

    private final FilterChain filterChain = mock(FilterChain.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(UserContextLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(UserContextLoggingFilter.class);
        logger.detachAppender(appender);
    }

    @Test
    void logsReceivedUserContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "1001");
        request.addHeader("X-User-Tenant", "acme");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserContextLoggingFilter().doFilter(request, response, filterChain);

        assertThat(appender.list).anyMatch(event ->
                event.getLevel().toString().equals("INFO")
                        && event.getFormattedMessage().contains("1001")
                        && event.getFormattedMessage().contains("acme"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotLogWithoutHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserContextLoggingFilter().doFilter(request, response, filterChain);

        assertThat(appender.list).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl weather-service test`
Expected: 编译失败，`UserContextLoggingFilter` 不存在。

- [ ] **Step 3: 实现 `UserContextLoggingFilter`**

```java
package com.example.weather.service.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public final class UserContextLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(UserContextLoggingFilter.class);
    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_TENANT = "X-User-Tenant";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader(X_USER_ID);
        String tenantId = request.getHeader(X_USER_TENANT);
        if (userId != null || tenantId != null) {
            logger.info("收到用户上下文: X-User-Id={}, X-User-Tenant={}", userId, tenantId);
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl weather-service test`
Expected: `UserContextLoggingFilterTest` 2 个用例全部 PASS，既有测试不受影响。

- [ ] **Step 5: 提交**

```bash
git add weather-service/src/main/java/com/example/weather/service/web/UserContextLoggingFilter.java \
  weather-service/src/test/java/com/example/weather/service/web/UserContextLoggingFilterTest.java
git commit -m "feat: log propagated user context in weather service"
```

---

### Task 5: 手工验收文档（README）

**Files:**
- Modify: `README.md`（在 "Verify the Business API" 一节之后新增 "用户上下文透传实验" 一节）

**Interfaces:**
- Consumes: Task 1–4 的配置项与行为（`USER_CONTEXT_SOURCE`、`USER_CONTEXT_JWT_SECRET`、两种模式、weather-service 日志输出格式 `收到用户上下文: X-User-Id={}, X-User-Tenant={}`）
- Produces: 无（文档）

- [ ] **Step 1: 在 README.md 的 "Verify the Business API" 之后新增以下内容**

```markdown
## 用户上下文透传实验

`weather-mcp-server` 支持两种入站请求拦截方式，通过 `USER_CONTEXT_SOURCE` 环境变量切换（默认 `bearer-token`），
提取的用户上下文通过 Feign 拦截器以 `X-User-Id` / `X-User-Tenant` 传给 `weather-service`，后者在日志中打印：

```text
收到用户上下文: X-User-Id=1001, X-User-Tenant=acme
```

### 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `USER_CONTEXT_SOURCE` | `bearer-token` | `bearer-token` 或 `explicit-headers` |
| `USER_CONTEXT_JWT_SECRET` | `demo-secret-change-me-0123456789abcdef` | HS256 密钥，需 ≥32 字节 |

### 生成测试 JWT

使用默认密钥生成带 `userId`/`tenantId` claims 的 token（有效期 1 小时）：

```bash
python3 - <<'EOF'
import base64, hashlib, hmac, json, time
secret = 'demo-secret-change-me-0123456789abcdef'
def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
header = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode())
payload = b64(json.dumps({'userId': '1001', 'tenantId': 'acme',
                          'exp': int(time.time()) + 3600}).encode())
sig = b64(hmac.new(secret.encode(), f'{header}.{payload}'.encode(), hashlib.sha256).digest())
print(f'{header}.{payload}.{sig}')
EOF
```

### 方式一：Bearer Token（默认）

按 [WeatherTool 测试说明](docs/weather-tool-testing.md) 启动 Eureka 与两个服务，用 curl 初始化会话并保存
`Mcp-Session-Id` 后，调用工具时附加 Authorization 头：

```bash
TOKEN=<上一步生成的 JWT>
curl --silent --show-error \
  -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <SESSION_ID>' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {"name": "get_weather_by_city", "arguments": {"city": "北京"}}
  }'
```

在 `weather-service` 终端应看到：`收到用户上下文: X-User-Id=1001, X-User-Tenant=acme`。

### 方式二：显式 Header

以 `explicit-headers` 模式重启 MCP Server，其余步骤同上，仅把请求头换成：

```bash
mvn -pl weather-mcp-server spring-boot:run \
  -Dspring-boot.run.arguments=--user-context.source=explicit-headers
```

```bash
curl --silent --show-error \
  -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <SESSION_ID>' \
  -H 'X-User-Id: 1001' \
  -H 'X-User-Tenant: acme' \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {"name": "get_weather_by_city", "arguments": {"city": "北京"}}
  }'
```

`weather-service` 终端应打印同样的用户上下文。

> 说明：token 缺失、无效或验签失败时请求不会被拒绝（本 demo 不做 MCP 认证），`weather-service` 日志也不会出现用户上下文。
```

- [ ] **Step 2: 全量构建验证**

Run: `mvn verify`
Expected: 两个模块全部测试 PASS（Task 1–4 的测试 + 既有测试）。

- [ ] **Step 3: 提交**

```bash
git add README.md
git commit -m "docs: add user context propagation experiment guide"
```

---

## 自检结果

- **Spec 覆盖**：两种拦截方式（Task 1/2）、Feign 透传（Task 3）、weather-service 打印（Task 4）、配置开关（Task 1）、错误处理与边界（测试断言覆盖：验签失败/过期/格式错误/缺 claim/缺 header/清理/空值跳过）、手工验收文档（Task 5）。全部有对应任务。
- **占位符**：无 TBD/TODO；每个代码步骤含完整代码。
- **类型一致性**：`UserContext(String, String)`、`UserContextHolder.set/get/clear`、`JwtTokenParser.parse`、`UserContextExtractionFilter(String, JwtTokenParser)`、`UserContextFeignInterceptor`、`UserContextLoggingFilter` 在各任务间签名一致。
