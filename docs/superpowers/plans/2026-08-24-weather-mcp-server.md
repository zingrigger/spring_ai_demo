# Spring AI Weather MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build two Eureka-registered Spring Boot applications so an external AI Agent can call a Streamable HTTP MCP weather tool that retrieves simulated weather data from a downstream service through OpenFeign.

**Architecture:** A Maven parent aggregates `weather-service` and `weather-mcp-server`. The MCP application exposes one Spring AI `@Tool`, resolves `weather-service` through Eureka and Spring Cloud LoadBalancer, and maps the REST response or downstream failure into an MCP-friendly result or error. The business service owns immutable in-memory weather data and a small REST contract.

**Tech Stack:** Java 21, Maven, Spring Boot 4.0.8, Spring AI 2.0.1 GA, Spring Cloud 2025.1.3, Spring MVC, Spring Cloud OpenFeign, Spring Cloud Netflix Eureka Client, Spring Boot Actuator, JUnit 5, AssertJ, Mockito, MockMvc.

## Global Constraints

- Use Java 21.
- Pin Spring Boot to `4.0.8`, Spring AI to `2.0.1`, and Spring Cloud to `2025.1.3`.
- Deliver only `weather-service` and `weather-mcp-server`; do not add an AI Agent, MCP Client, or Eureka Server module.
- Use Spring MVC and blocking OpenFeign; do not introduce WebFlux.
- Expose MCP over Streamable HTTP at `/mcp` with `spring.ai.mcp.server.protocol=STREAMABLE` set explicitly.
- Register both applications with Eureka at `${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}`.
- Keep weather data in immutable in-memory structures; do not add a database, cache, or third-party weather API.
- Expose only one MCP tool named `get_weather_by_city`.
- Do not add authentication, retry, circuit breaker, gateway, or production deployment infrastructure.
- Do not add a shared DTO module; each application owns its boundary types.
- Tests must run without an external Eureka Server.

---

## File Map

### Root

- `.gitignore`: excludes Maven output and common IDE metadata.
- `pom.xml`: aggregates both modules and imports the Spring AI and Spring Cloud BOMs.
- `README.md`: documents architecture, startup order, configuration, and manual MCP verification.

### `weather-service`

- `weather-service/pom.xml`: business-service dependencies and executable-jar plugin.
- `weather-service/src/main/java/com/example/weather/service/WeatherServiceApplication.java`: application entry point.
- `weather-service/src/main/java/com/example/weather/service/weather/WeatherReading.java`: immutable business result.
- `weather-service/src/main/java/com/example/weather/service/weather/WeatherRepository.java`: repository boundary.
- `weather-service/src/main/java/com/example/weather/service/weather/InMemoryWeatherRepository.java`: fixed city and alias data.
- `weather-service/src/main/java/com/example/weather/service/weather/WeatherQueryService.java`: validation, normalization, and lookup.
- `weather-service/src/main/java/com/example/weather/service/weather/InvalidCityException.java`: blank-city domain error.
- `weather-service/src/main/java/com/example/weather/service/weather/UnsupportedCityException.java`: unknown-city domain error.
- `weather-service/src/main/java/com/example/weather/service/web/WeatherController.java`: `GET /api/weather/{city}`.
- `weather-service/src/main/java/com/example/weather/service/web/WeatherExceptionHandler.java`: RFC 9457 `ProblemDetail` mapping.
- `weather-service/src/main/resources/application.yml`: port, service name, Eureka, and Actuator configuration.
- `weather-service/src/test/resources/application-test.yml`: disables Eureka in tests.
- `weather-service/src/test/java/com/example/weather/service/WeatherServiceApplicationTest.java`: application configuration smoke test.
- `weather-service/src/test/java/com/example/weather/service/weather/WeatherQueryServiceTest.java`: domain behavior tests.
- `weather-service/src/test/java/com/example/weather/service/web/WeatherControllerTest.java`: REST contract tests.

### `weather-mcp-server`

- `weather-mcp-server/pom.xml`: Spring AI MCP, Feign, discovery, Actuator, and test dependencies.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/WeatherMcpServerApplication.java`: application entry point and Feign enablement.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/client/WeatherServiceClient.java`: Eureka-backed Feign contract.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/client/WeatherServiceResponse.java`: downstream response type.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherToolResult.java`: MCP tool result type.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherToolException.java`: sanitized tool execution error.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherTool.java`: Spring AI `@Tool` and failure translation.
- `weather-mcp-server/src/main/java/com/example/weather/mcp/config/WeatherToolConfiguration.java`: `ToolCallbackProvider` registration.
- `weather-mcp-server/src/main/resources/application.yml`: Streamable HTTP, Feign timeout, Eureka, and Actuator configuration.
- `weather-mcp-server/src/test/resources/application-test.yml`: disables Eureka and discovery network access.
- `weather-mcp-server/src/test/java/com/example/weather/mcp/WeatherMcpServerApplicationTest.java`: application and MCP property smoke test.
- `weather-mcp-server/src/test/java/com/example/weather/mcp/tool/WeatherToolTest.java`: tool behavior and downstream error tests.
- `weather-mcp-server/src/test/java/com/example/weather/mcp/config/WeatherToolConfigurationTest.java`: tool name and JSON Schema registration test.

---

### Task 1: Maven Foundation and Boot Applications

**Files:**
- Create: `.gitignore`
- Create: `pom.xml`
- Create: `weather-service/pom.xml`
- Create: `weather-mcp-server/pom.xml`
- Create: `weather-service/src/test/java/com/example/weather/service/WeatherServiceApplicationTest.java`
- Create: `weather-mcp-server/src/test/java/com/example/weather/mcp/WeatherMcpServerApplicationTest.java`
- Create: `weather-service/src/main/java/com/example/weather/service/WeatherServiceApplication.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/WeatherMcpServerApplication.java`
- Create: `weather-service/src/main/resources/application.yml`
- Create: `weather-mcp-server/src/main/resources/application.yml`
- Create: `weather-service/src/test/resources/application-test.yml`
- Create: `weather-mcp-server/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: No application code; uses the exact versions in Global Constraints.
- Produces: Executable applications `WeatherServiceApplication` and `WeatherMcpServerApplication`, Maven modules named `weather-service` and `weather-mcp-server`, and runtime configuration keys used by every later task.

- [ ] **Step 1: Create the parent and module build files**

Create `.gitignore`:

```gitignore
target/
.idea/
*.iml
.classpath
.project
.settings/
```

Create the root `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.8</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>spring-ai-weather-demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>weather-service</module>
        <module>weather-mcp-server</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.1</spring-ai.version>
        <spring-cloud.version>2025.1.3</spring-cloud.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

Create `weather-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>spring-ai-weather-demo</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>weather-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Create `weather-mcp-server/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>spring-ai-weather-demo</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>weather-mcp-server</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write application tests before entry points exist**

Create `WeatherServiceApplicationTest.java`:

```java
package com.example.weather.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WeatherServiceApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void loadsWeatherServiceConfiguration() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("weather-service");
        assertThat(environment.getProperty("server.port"))
                .isEqualTo("8082");
    }
}
```

Create `WeatherMcpServerApplicationTest.java`:

```java
package com.example.weather.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WeatherMcpServerApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void loadsStreamableMcpConfiguration() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("weather-mcp-server");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol"))
                .isEqualTo("STREAMABLE");
        assertThat(environment.getProperty(
                "spring.ai.mcp.server.streamable-http.mcp-endpoint"))
                .isEqualTo("/mcp");
    }
}
```

- [ ] **Step 3: Run the tests and verify Spring Boot configuration discovery fails**

Run:

```bash
mvn test
```

Expected: FAIL at test startup with `Unable to find a @SpringBootConfiguration` because the application entry points do not exist.

- [ ] **Step 4: Add application entry points and runtime configuration**

Create `WeatherServiceApplication.java`:

```java
package com.example.weather.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WeatherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherServiceApplication.class, args);
    }
}
```

Create `WeatherMcpServerApplication.java`:

```java
package com.example.weather.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class WeatherMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherMcpServerApplication.class, args);
    }
}
```

Create `weather-service/src/main/resources/application.yml`:

```yaml
server:
  port: ${SERVER_PORT:8082}

spring:
  application:
    name: weather-service

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  info:
    env:
      enabled: true

info:
  app:
    name: ${spring.application.name}
    description: Simulated weather business service
```

Create `weather-mcp-server/src/main/resources/application.yml`:

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: weather-mcp-server
  ai:
    mcp:
      server:
        name: weather-mcp-server
        version: 1.0.0
        protocol: STREAMABLE
        streamable-http:
          mcp-endpoint: /mcp
  cloud:
    openfeign:
      client:
        config:
          weather-service:
            connectTimeout: 2000
            readTimeout: 3000

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  info:
    env:
      enabled: true

info:
  app:
    name: ${spring.application.name}
    description: Streamable HTTP weather MCP server
```

Create the same test profile in each module at `src/test/resources/application-test.yml`:

```yaml
eureka:
  client:
    enabled: false
    register-with-eureka: false
    fetch-registry: false

spring:
  cloud:
    discovery:
      enabled: false
```

- [ ] **Step 5: Run both application tests**

Run:

```bash
mvn test
```

Expected: PASS with one test in each module and no attempt to contact Eureka.

- [ ] **Step 6: Commit the build foundation**

```bash
git add .gitignore pom.xml weather-service weather-mcp-server
git commit -m "build: scaffold weather services"
```

---

### Task 2: Weather Service Domain and REST API

**Files:**
- Create: `weather-service/src/test/java/com/example/weather/service/weather/WeatherQueryServiceTest.java`
- Create: `weather-service/src/test/java/com/example/weather/service/web/WeatherControllerTest.java`
- Create: `weather-service/src/main/java/com/example/weather/service/weather/WeatherReading.java`
- Create: `weather-service/src/main/java/com/example/weather/service/weather/WeatherRepository.java`
- Create: `weather-service/src/main/java/com/example/weather/service/weather/InMemoryWeatherRepository.java`
- Create: `weather-service/src/main/java/com/example/weather/service/weather/WeatherQueryService.java`
- Create: `weather-service/src/main/java/com/example/weather/service/weather/InvalidCityException.java`
- Create: `weather-service/src/main/java/com/example/weather/service/weather/UnsupportedCityException.java`
- Create: `weather-service/src/main/java/com/example/weather/service/web/WeatherController.java`
- Create: `weather-service/src/main/java/com/example/weather/service/web/WeatherExceptionHandler.java`

**Interfaces:**
- Consumes: `WeatherServiceApplication` from Task 1.
- Produces: `WeatherQueryService#getCurrentWeather(String): WeatherReading` and `GET /api/weather/{city}` returning the exact `WeatherReading` JSON fields used by the Feign contract in Task 3.

- [ ] **Step 1: Write failing domain tests**

Create `WeatherQueryServiceTest.java`:

```java
package com.example.weather.service.weather;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherQueryServiceTest {

    private WeatherQueryService service;

    @BeforeEach
    void setUp() {
        service = new WeatherQueryService(new InMemoryWeatherRepository());
    }

    @Test
    void returnsWeatherForChineseCityName() {
        WeatherReading result = service.getCurrentWeather("北京");

        assertThat(result.city()).isEqualTo("北京");
        assertThat(result.condition()).isEqualTo("晴");
    }

    @Test
    void mapsEnglishAliasIgnoringCaseAndWhitespace() {
        WeatherReading result = service.getCurrentWeather("  ShAnGhAi  ");

        assertThat(result.city()).isEqualTo("上海");
    }

    @Test
    void rejectsBlankCity() {
        assertThatThrownBy(() -> service.getCurrentWeather("  "))
                .isInstanceOf(InvalidCityException.class)
                .hasMessage("城市名称不能为空");
    }

    @Test
    void rejectsUnsupportedCity() {
        assertThatThrownBy(() -> service.getCurrentWeather("Atlantis"))
                .isInstanceOf(UnsupportedCityException.class)
                .hasMessage("暂不支持城市：Atlantis");
    }
}
```

- [ ] **Step 2: Run the domain test and verify it fails**

Run:

```bash
mvn -pl weather-service -Dtest=WeatherQueryServiceTest test
```

Expected: FAIL during test compilation because the weather domain types do not exist.

- [ ] **Step 3: Implement the weather domain and fixed data**

Create `WeatherReading.java`:

```java
package com.example.weather.service.weather;

import java.math.BigDecimal;

public record WeatherReading(
        String city,
        String condition,
        BigDecimal temperatureCelsius,
        BigDecimal feelsLikeCelsius,
        int humidityPercent,
        BigDecimal windSpeedKph) {
}
```

Create `WeatherRepository.java`:

```java
package com.example.weather.service.weather;

import java.util.Optional;

public interface WeatherRepository {

    Optional<WeatherReading> findByAlias(String normalizedAlias);
}
```

Create `InvalidCityException.java`:

```java
package com.example.weather.service.weather;

public final class InvalidCityException extends RuntimeException {

    public InvalidCityException() {
        super("城市名称不能为空");
    }
}
```

Create `UnsupportedCityException.java`:

```java
package com.example.weather.service.weather;

public final class UnsupportedCityException extends RuntimeException {

    private final String city;

    public UnsupportedCityException(String city) {
        super("暂不支持城市：" + city);
        this.city = city;
    }

    public String city() {
        return city;
    }
}
```

Create `InMemoryWeatherRepository.java`:

```java
package com.example.weather.service.weather;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public final class InMemoryWeatherRepository implements WeatherRepository {

    private static final WeatherReading BEIJING = new WeatherReading(
            "北京", "晴", new BigDecimal("26.5"), new BigDecimal("27.1"),
            42, new BigDecimal("10.8"));
    private static final WeatherReading SHANGHAI = new WeatherReading(
            "上海", "多云", new BigDecimal("24.0"), new BigDecimal("25.2"),
            68, new BigDecimal("14.4"));
    private static final WeatherReading GUANGZHOU = new WeatherReading(
            "广州", "阵雨", new BigDecimal("30.2"), new BigDecimal("34.0"),
            78, new BigDecimal("8.6"));
    private static final WeatherReading SHENZHEN = new WeatherReading(
            "深圳", "多云", new BigDecimal("29.4"), new BigDecimal("32.1"),
            74, new BigDecimal("12.2"));
    private static final WeatherReading HANGZHOU = new WeatherReading(
            "杭州", "小雨", new BigDecimal("23.6"), new BigDecimal("24.3"),
            81, new BigDecimal("9.5"));

    private final Map<String, WeatherReading> readings = Map.ofEntries(
            Map.entry("北京", BEIJING),
            Map.entry("beijing", BEIJING),
            Map.entry("上海", SHANGHAI),
            Map.entry("shanghai", SHANGHAI),
            Map.entry("广州", GUANGZHOU),
            Map.entry("guangzhou", GUANGZHOU),
            Map.entry("深圳", SHENZHEN),
            Map.entry("shenzhen", SHENZHEN),
            Map.entry("杭州", HANGZHOU),
            Map.entry("hangzhou", HANGZHOU));

    @Override
    public Optional<WeatherReading> findByAlias(String normalizedAlias) {
        return Optional.ofNullable(readings.get(normalizedAlias));
    }
}
```

Create `WeatherQueryService.java`:

```java
package com.example.weather.service.weather;

import java.util.Locale;

import org.springframework.stereotype.Service;

@Service
public final class WeatherQueryService {

    private final WeatherRepository repository;

    public WeatherQueryService(WeatherRepository repository) {
        this.repository = repository;
    }

    public WeatherReading getCurrentWeather(String city) {
        if (city == null || city.isBlank()) {
            throw new InvalidCityException();
        }
        String strippedCity = city.strip();
        String normalizedAlias = strippedCity.toLowerCase(Locale.ROOT);
        return repository.findByAlias(normalizedAlias)
                .orElseThrow(() -> new UnsupportedCityException(strippedCity));
    }
}
```

- [ ] **Step 4: Run the domain test and verify it passes**

Run:

```bash
mvn -pl weather-service -Dtest=WeatherQueryServiceTest test
```

Expected: PASS with four tests.

- [ ] **Step 5: Write failing REST contract tests**

Create `WeatherControllerTest.java`:

```java
package com.example.weather.service.web;

import java.math.BigDecimal;

import com.example.weather.service.weather.InvalidCityException;
import com.example.weather.service.weather.UnsupportedCityException;
import com.example.weather.service.weather.WeatherQueryService;
import com.example.weather.service.weather.WeatherReading;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherController.class)
@Import(WeatherExceptionHandler.class)
class WeatherControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherQueryService weatherQueryService;

    @Test
    void returnsStructuredWeather() throws Exception {
        WeatherReading reading = new WeatherReading(
                "北京", "晴", new BigDecimal("26.5"),
                new BigDecimal("27.1"), 42, new BigDecimal("10.8"));
        when(weatherQueryService.getCurrentWeather("北京")).thenReturn(reading);

        mockMvc.perform(get("/api/weather/{city}", "北京"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("北京"))
                .andExpect(jsonPath("$.condition").value("晴"))
                .andExpect(jsonPath("$.temperatureCelsius").value(26.5))
                .andExpect(jsonPath("$.feelsLikeCelsius").value(27.1))
                .andExpect(jsonPath("$.humidityPercent").value(42))
                .andExpect(jsonPath("$.windSpeedKph").value(10.8));
    }

    @Test
    void returnsBadRequestForBlankCity() throws Exception {
        when(weatherQueryService.getCurrentWeather(" "))
                .thenThrow(new InvalidCityException());

        mockMvc.perform(get("/api/weather/{city}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid city"))
                .andExpect(jsonPath("$.detail").value("城市名称不能为空"));
    }

    @Test
    void returnsNotFoundForUnsupportedCity() throws Exception {
        when(weatherQueryService.getCurrentWeather("Atlantis"))
                .thenThrow(new UnsupportedCityException("Atlantis"));

        mockMvc.perform(get("/api/weather/{city}", "Atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unsupported city"))
                .andExpect(jsonPath("$.detail").value("暂不支持城市：Atlantis"))
                .andExpect(jsonPath("$.city").value("Atlantis"));
    }
}
```

- [ ] **Step 6: Run the REST test and verify it fails**

Run:

```bash
mvn -pl weather-service -Dtest=WeatherControllerTest test
```

Expected: FAIL during test compilation because `WeatherController` and `WeatherExceptionHandler` do not exist.

- [ ] **Step 7: Implement the controller and ProblemDetail mapping**

Create `WeatherController.java`:

```java
package com.example.weather.service.web;

import com.example.weather.service.weather.WeatherQueryService;
import com.example.weather.service.weather.WeatherReading;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public final class WeatherController {

    private final WeatherQueryService weatherQueryService;

    public WeatherController(WeatherQueryService weatherQueryService) {
        this.weatherQueryService = weatherQueryService;
    }

    @GetMapping("/{city}")
    public WeatherReading getCurrentWeather(@PathVariable String city) {
        return weatherQueryService.getCurrentWeather(city);
    }
}
```

Create `WeatherExceptionHandler.java`:

```java
package com.example.weather.service.web;

import com.example.weather.service.weather.InvalidCityException;
import com.example.weather.service.weather.UnsupportedCityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class WeatherExceptionHandler {

    @ExceptionHandler(InvalidCityException.class)
    ProblemDetail handleInvalidCity(InvalidCityException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid city");
        return problem;
    }

    @ExceptionHandler(UnsupportedCityException.class)
    ProblemDetail handleUnsupportedCity(UnsupportedCityException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Unsupported city");
        problem.setProperty("city", exception.city());
        return problem;
    }
}
```

- [ ] **Step 8: Run all Weather Service tests**

Run:

```bash
mvn -pl weather-service test
```

Expected: PASS for application, domain, and MVC tests.

- [ ] **Step 9: Commit the Weather Service vertical slice**

```bash
git add weather-service/src
git commit -m "feat: add simulated weather service"
```

---

### Task 3: Feign-Backed Spring AI Weather Tool

**Files:**
- Create: `weather-mcp-server/src/test/java/com/example/weather/mcp/tool/WeatherToolTest.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/client/WeatherServiceClient.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/client/WeatherServiceResponse.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherToolResult.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherToolException.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/tool/WeatherTool.java`

**Interfaces:**
- Consumes: Weather Service `GET /api/weather/{city}` and its six response fields from Task 2.
- Produces: `WeatherServiceClient#getCurrentWeather(String): WeatherServiceResponse` and `WeatherTool#getWeatherByCity(String): WeatherToolResult`, annotated as MCP tool `get_weather_by_city`.

- [ ] **Step 1: Write failing tool behavior tests**

Create `WeatherToolTest.java`:

```java
package com.example.weather.mcp.tool;

import java.math.BigDecimal;

import com.example.weather.mcp.client.WeatherServiceClient;
import com.example.weather.mcp.client.WeatherServiceResponse;
import feign.FeignException;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherToolTest {

    private WeatherServiceClient client;
    private WeatherTool tool;

    @BeforeEach
    void setUp() {
        client = mock(WeatherServiceClient.class);
        tool = new WeatherTool(client);
    }

    @Test
    void returnsMappedWeatherResult() {
        WeatherServiceResponse response = new WeatherServiceResponse(
                "北京", "晴", new BigDecimal("26.5"),
                new BigDecimal("27.1"), 42, new BigDecimal("10.8"));
        when(client.getCurrentWeather("北京")).thenReturn(response);

        WeatherToolResult result = tool.getWeatherByCity("  北京  ");

        assertThat(result.city()).isEqualTo("北京");
        assertThat(result.temperatureCelsius()).isEqualByComparingTo("26.5");
        verify(client).getCurrentWeather("北京");
    }

    @Test
    void rejectsBlankCityBeforeCallingFeign() {
        assertThatThrownBy(() -> tool.getWeatherByCity(" "))
                .isInstanceOf(WeatherToolException.class)
                .hasMessage("城市名称不能为空");
        verify(client, never()).getCurrentWeather(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void translatesNotFoundWithoutLeakingFeignDetails() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(404);
        when(client.getCurrentWeather("Atlantis")).thenThrow(exception);

        assertThatThrownBy(() -> tool.getWeatherByCity("Atlantis"))
                .isInstanceOf(WeatherToolException.class)
                .hasMessage("暂不支持城市：Atlantis");
    }

    @Test
    void translatesNoAvailableInstance() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(503);
        when(client.getCurrentWeather("北京")).thenThrow(exception);

        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class)
                .hasMessage("天气服务暂时不可用，请稍后重试");
    }

    @Test
    void translatesConnectionTimeout() {
        RetryableException exception = mock(RetryableException.class);
        when(client.getCurrentWeather("北京")).thenThrow(exception);

        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class)
                .hasMessage("天气服务暂时不可用，请稍后重试");
    }

    @Test
    void sanitizesUnexpectedFeignFailure() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(500);
        when(client.getCurrentWeather("北京")).thenThrow(exception);

        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class)
                .hasMessage("查询天气失败");
    }

    @Test
    void sanitizesUnexpectedResponseMappingFailure() {
        when(client.getCurrentWeather("北京")).thenReturn(null);

        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class)
                .hasMessage("查询天气失败");
    }
}
```

- [ ] **Step 2: Run the tool test and verify it fails**

Run:

```bash
mvn -pl weather-mcp-server -Dtest=WeatherToolTest test
```

Expected: FAIL during test compilation because the client and tool types do not exist.

- [ ] **Step 3: Implement the Feign boundary types**

Create `WeatherServiceResponse.java`:

```java
package com.example.weather.mcp.client;

import java.math.BigDecimal;

public record WeatherServiceResponse(
        String city,
        String condition,
        BigDecimal temperatureCelsius,
        BigDecimal feelsLikeCelsius,
        int humidityPercent,
        BigDecimal windSpeedKph) {
}
```

Create `WeatherServiceClient.java`:

```java
package com.example.weather.mcp.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "weather-service", path = "/api/weather")
public interface WeatherServiceClient {

    @GetMapping("/{city}")
    WeatherServiceResponse getCurrentWeather(@PathVariable("city") String city);
}
```

- [ ] **Step 4: Implement the Spring AI tool and sanitized errors**

Create `WeatherToolResult.java`:

```java
package com.example.weather.mcp.tool;

import java.math.BigDecimal;

public record WeatherToolResult(
        String city,
        String condition,
        BigDecimal temperatureCelsius,
        BigDecimal feelsLikeCelsius,
        int humidityPercent,
        BigDecimal windSpeedKph) {
}
```

Create `WeatherToolException.java`:

```java
package com.example.weather.mcp.tool;

public final class WeatherToolException extends RuntimeException {

    public WeatherToolException(String message) {
        super(message);
    }

    public WeatherToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `WeatherTool.java`:

```java
package com.example.weather.mcp.tool;

import com.example.weather.mcp.client.WeatherServiceClient;
import com.example.weather.mcp.client.WeatherServiceResponse;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public final class WeatherTool {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTool.class);
    private static final String SERVICE_UNAVAILABLE =
            "天气服务暂时不可用，请稍后重试";

    private final WeatherServiceClient weatherServiceClient;

    public WeatherTool(WeatherServiceClient weatherServiceClient) {
        this.weatherServiceClient = weatherServiceClient;
    }

    @Tool(name = "get_weather_by_city", description = "查询指定城市的当前天气")
    public WeatherToolResult getWeatherByCity(
            @ToolParam(description = "城市名称，例如北京或 Beijing") String city) {
        if (city == null || city.isBlank()) {
            throw new WeatherToolException("城市名称不能为空");
        }
        String strippedCity = city.strip();
        try {
            return toToolResult(weatherServiceClient.getCurrentWeather(strippedCity));
        }
        catch (RetryableException exception) {
            logger.warn("Weather service unavailable for city {}", strippedCity, exception);
            throw new WeatherToolException(SERVICE_UNAVAILABLE, exception);
        }
        catch (FeignException exception) {
            if (exception.status() == 404) {
                logger.info("Weather service does not support city {}", strippedCity);
                throw new WeatherToolException(
                        "暂不支持城市：" + strippedCity, exception);
            }
            if (exception.status() == 503 || exception.status() == 504) {
                logger.warn("Weather service unavailable for city {}", strippedCity, exception);
                throw new WeatherToolException(SERVICE_UNAVAILABLE, exception);
            }
            logger.error("Weather service request failed for city {}", strippedCity, exception);
            throw new WeatherToolException("查询天气失败", exception);
        }
        catch (RuntimeException exception) {
            logger.error("Unexpected weather tool failure for city {}", strippedCity, exception);
            throw new WeatherToolException("查询天气失败", exception);
        }
    }

    private WeatherToolResult toToolResult(WeatherServiceResponse response) {
        return new WeatherToolResult(
                response.city(),
                response.condition(),
                response.temperatureCelsius(),
                response.feelsLikeCelsius(),
                response.humidityPercent(),
                response.windSpeedKph());
    }
}
```

- [ ] **Step 5: Run the tool tests**

Run:

```bash
mvn -pl weather-mcp-server -Dtest=WeatherToolTest test
```

Expected: PASS with seven tests covering success, validation, `404`, `503`, timeout, Feign failure, and response-mapping failure.

- [ ] **Step 6: Run all MCP module tests**

Run:

```bash
mvn -pl weather-mcp-server test
```

Expected: PASS for the application smoke test and tool tests without contacting Eureka.

- [ ] **Step 7: Commit the Feign-backed tool**

```bash
git add weather-mcp-server/src
git commit -m "feat: add Feign-backed weather tool"
```

---

### Task 4: MCP ToolCallback Registration

**Files:**
- Create: `weather-mcp-server/src/test/java/com/example/weather/mcp/config/WeatherToolConfigurationTest.java`
- Create: `weather-mcp-server/src/main/java/com/example/weather/mcp/config/WeatherToolConfiguration.java`

**Interfaces:**
- Consumes: `WeatherTool` from Task 3.
- Produces: Spring bean `weatherToolCallbackProvider` containing exactly one ToolCallback named `get_weather_by_city`, which the MCP Starter converts into an MCP tool.

- [ ] **Step 1: Write a failing registration and schema test**

Create `WeatherToolConfigurationTest.java`:

```java
package com.example.weather.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WeatherToolConfigurationTest {

    @Autowired
    @Qualifier("weatherToolCallbackProvider")
    private ToolCallbackProvider provider;

    @Test
    void registersWeatherToolWithCityInputSchema() {
        assertThat(provider.getToolCallbacks())
                .singleElement()
                .satisfies(callback -> {
                    assertThat(callback.getToolDefinition().name())
                            .isEqualTo("get_weather_by_city");
                    assertThat(callback.getToolDefinition().description())
                            .isEqualTo("查询指定城市的当前天气");
                    assertThat(callback.getToolDefinition().inputSchema())
                            .contains("city");
                });
    }
}
```

- [ ] **Step 2: Run the test and verify the provider is missing**

Run:

```bash
mvn -pl weather-mcp-server -Dtest=WeatherToolConfigurationTest test
```

Expected: FAIL while creating the test because no bean qualified as `weatherToolCallbackProvider` exists.

- [ ] **Step 3: Register the annotated tool with Spring AI**

Create `WeatherToolConfiguration.java`:

```java
package com.example.weather.mcp.config;

import com.example.weather.mcp.tool.WeatherTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WeatherToolConfiguration {

    @Bean
    ToolCallbackProvider weatherToolCallbackProvider(WeatherTool weatherTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool)
                .build();
    }
}
```

- [ ] **Step 4: Run the registration test**

Run:

```bash
mvn -pl weather-mcp-server -Dtest=WeatherToolConfigurationTest test
```

Expected: PASS and confirm the registered tool name, description, and `city` input field.

- [ ] **Step 5: Run the whole reactor test suite**

Run:

```bash
mvn test
```

Expected: PASS in both modules with no Eureka dependency.

- [ ] **Step 6: Commit MCP registration**

```bash
git add weather-mcp-server/src
git commit -m "feat: expose weather tool through MCP"
```

---

### Task 5: Documentation and Release Verification

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: Ports, environment variables, REST path, MCP path, and tool name from Tasks 1–4.
- Produces: Reproducible setup and verification instructions for a developer who already has an Eureka Server.

- [ ] **Step 1: Write the user-facing README**

Create `README.md` with the following exact sections and commands:

```markdown
# Spring AI Weather MCP Demo

This project demonstrates an external AI Agent calling a Spring AI MCP Server over Streamable HTTP. The MCP Server calls a separate Weather Service through OpenFeign, Spring Cloud LoadBalancer, and Eureka.

## Modules

- `weather-service` — simulated weather REST API on port `8082`
- `weather-mcp-server` — Streamable HTTP MCP Server on port `8081`

An AI Agent, MCP Client, and Eureka Server are intentionally not included.

## Requirements

- Java 21
- Maven 3.9+
- Eureka Server available at `http://localhost:8761/eureka/`, or set `EUREKA_DEFAULT_ZONE`

## Build and Test

```bash
mvn verify
```

The test profile disables Eureka registration, so the automated suite does not require external infrastructure.

## Run

Start the existing Eureka Server first. Then use two terminals:

```bash
mvn -pl weather-service spring-boot:run
```

```bash
mvn -pl weather-mcp-server spring-boot:run
```

To use another Eureka Server:

```bash
EUREKA_DEFAULT_ZONE=http://eureka-host:8761/eureka/ \
  mvn -pl weather-service spring-boot:run
```

Apply the same environment variable when starting `weather-mcp-server`.

## Verify the Business API

```bash
curl http://localhost:8082/api/weather/%E5%8C%97%E4%BA%AC
```

Expected fields are `city`, `condition`, `temperatureCelsius`, `feelsLikeCelsius`, `humidityPercent`, and `windSpeedKph`.

Supported cities are 北京/Beijing, 上海/Shanghai, 广州/Guangzhou, 深圳/Shenzhen, and 杭州/Hangzhou.

## Connect an MCP Client

The Streamable HTTP endpoint is:

```text
http://localhost:8081/mcp
```

Launch MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
```

In the Inspector, select Streamable HTTP, enter `http://localhost:8081/mcp`, connect, and invoke:

```json
{
  "name": "get_weather_by_city",
  "arguments": {
    "city": "北京"
  }
}
```

Querying an unsupported city returns a sanitized tool error. If no Weather Service instance is available in Eureka, the tool reports that the weather service is temporarily unavailable.

## Health Endpoints

- `http://localhost:8081/actuator/health`
- `http://localhost:8082/actuator/health`

## Security Notice

This demo does not implement MCP authentication. Do not expose `/mcp` directly to the public internet. A production deployment must add TLS, authentication, authorization, and network access controls in the application or an upstream gateway.
```

- [ ] **Step 2: Run formatting and placeholder checks**

Run:

```bash
git diff --check
rg -n "T[B]D|T[O]DO|implement[ ]later|fill[ ]in" README.md \
  weather-service weather-mcp-server
```

Expected: `git diff --check` exits successfully and `rg` prints no matches.

- [ ] **Step 3: Run the full verification build**

Run:

```bash
mvn clean verify
```

Expected: reactor summary reports `SUCCESS` for the parent, `weather-service`, and `weather-mcp-server`.

- [ ] **Step 4: Inspect executable artifacts**

Run:

```bash
ls -lh weather-service/target/weather-service-0.0.1-SNAPSHOT.jar \
  weather-mcp-server/target/weather-mcp-server-0.0.1-SNAPSHOT.jar
```

Expected: both executable jar files exist and are non-empty.

- [ ] **Step 5: Confirm repository scope**

Run:

```bash
git status --short
find . -maxdepth 2 -type d | sort
```

Expected: only the two requested application modules, root documentation, Maven output, and Git metadata are present; there is no Agent, MCP Client, or Eureka Server module.

- [ ] **Step 6: Commit documentation**

```bash
git add README.md
git commit -m "docs: add weather MCP usage guide"
```

- [ ] **Step 7: Record the final verification result**

Run:

```bash
git status --short --branch
git log --oneline --decorate -6
```

Expected: the working tree is clean and the history contains the build, Weather Service, Feign tool, MCP registration, and documentation commits.
