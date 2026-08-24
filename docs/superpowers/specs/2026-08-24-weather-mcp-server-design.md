# Spring AI Weather MCP Server 设计

## 目标

构建一个 Java 21、Maven 多模块示例项目，演示外部 AI Agent 通过 Streamable HTTP 调用 Spring AI MCP Server，MCP Server 再通过 OpenFeign 和 Eureka 服务发现访问天气业务服务。

交付两个可独立启动的应用：

- `weather-mcp-server`：提供 MCP 天气工具。
- `weather-service`：提供基于内存模拟数据的天气 REST API。

现有 Eureka Server 和外部 AI Agent 不属于交付范围。

## 技术基线

| 技术 | 版本或选择 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.0.8 |
| Spring AI | 2.0.1 GA |
| Spring Cloud | 2025.1.3 |
| MCP 传输 | Streamable HTTP |
| Web 技术栈 | Spring MVC |
| 服务调用 | Spring Cloud OpenFeign |
| 服务注册与发现 | Netflix Eureka Client |
| 构建工具 | Maven 多模块 |

Spring Cloud 2025.1.3 支持 Spring Boot 4.0.x；Spring AI 2.0.1 支持 Spring Boot 4.0.x 和 4.1.x。本项目固定使用 Spring Boot 4.0.8，避免引入不必要的版本浮动。

## 范围

### 包含

- 两个应用注册到已有 Eureka Server。
- MCP Server 使用 Streamable HTTP，在 `/mcp` 暴露工具。
- 一个 `get_weather_by_city` 工具。
- MCP Server 使用 Feign Client 按服务名调用 `weather-service`。
- Weather Service 使用预置内存数据。
- 明确的输入校验、业务错误和下游故障转换。
- 自动化测试与本地手工验收说明。

### 不包含

- Spring AI MCP Client 或 AI Agent。
- Eureka Server。
- 数据库、缓存、第三方天气 API。
- 认证鉴权、API Gateway、熔断和自动重试。
- 天气预报、经纬度查询或多个 MCP 工具。
- 生产部署配置。

## 工程结构

父工程只负责模块聚合、依赖管理和构建，不作为运行应用：

```text
spring-ai-weather-demo
├── pom.xml
├── weather-service
│   ├── pom.xml
│   └── src
└── weather-mcp-server
    ├── pom.xml
    └── src
```

不增加公共 DTO 模块。REST API 是两个应用之间的显式边界，各模块维护自己的请求与响应类型，避免共享内部模型造成编译期耦合。

## 总体架构

```text
External AI Agent
  └─ Streamable HTTP
     └─ weather-mcp-server:8081/mcp
        └─ Spring AI @Tool / ToolCallbackProvider
           └─ OpenFeign: weather-service
              └─ Spring Cloud LoadBalancer + Eureka
                 └─ weather-service:8082/api/weather/{city}
```

Eureka 负责 `weather-mcp-server` 到 `weather-service` 的服务发现。外部 AI Agent 通过明确配置的 MCP URL 连接服务器；MCP Client 不会自动通过 Eureka 发现 MCP Server。

## 组件设计

### Weather Service

Weather Service 负责天气业务数据和 REST 契约，不依赖 Spring AI。

内部组件：

- Controller：暴露天气查询 REST API，负责 HTTP 参数和状态码。
- Weather query service：根据规范化城市名查找天气。
- In-memory repository：保存不可变的预置模拟数据。
- Exception handler：将输入错误和未知城市转换为 `ProblemDetail`。

预置北京、上海、广州、深圳和杭州的数据。每个城市接受中文名称和对应英文名称，英文查询忽略大小写。服务返回规范化后的中文城市名。

### Weather MCP Server

Weather MCP Server 负责把 MCP 工具调用转换成业务服务调用，不保存天气业务数据。

内部组件：

- MCP tool service：提供带 `@Tool` 和 `@ToolParam` 的工具方法。
- `ToolCallbackProvider` 配置：将工具方法注册到 Spring AI。
- Feign Client：以 Eureka 服务名 `weather-service` 调用 REST API。
- Tool error translator：将 Feign 和服务发现异常转换为简洁的工具错误。

选择 Spring AI `@Tool` 与 `ToolCallbackProvider`，由 MCP Server Starter 默认启用的 ToolCallback converter 将 Spring AI Tool 转换为 MCP Tool。相比直接使用 `@McpTool`，该方式更能演示 Spring AI 的通用工具抽象；相比手工构造 MCP SDK ToolSpecification，它所需的样板代码更少。

## REST API 契约

### 查询当前天气

```http
GET /api/weather/{city}
```

成功响应状态为 `200 OK`：

```json
{
  "city": "北京",
  "condition": "晴",
  "temperatureCelsius": 26.5,
  "feelsLikeCelsius": 27.1,
  "humidityPercent": 42,
  "windSpeedKph": 10.8
}
```

字段约束：

- `city`、`condition`：非空字符串。
- `temperatureCelsius`、`feelsLikeCelsius`：摄氏温度。
- `humidityPercent`：0 到 100 的整数。
- `windSpeedKph`：非负数，单位为千米每小时。

错误响应使用 Spring `ProblemDetail`：

- 城市参数为空或仅包含空白：`400 Bad Request`。
- 城市不在预置数据中：`404 Not Found`。

## MCP 工具契约

工具定义：

| 属性 | 值 |
| --- | --- |
| 名称 | `get_weather_by_city` |
| 描述 | 查询指定城市的当前天气 |
| 参数 | 必填字符串 `city` |
| 返回 | 结构化天气对象 |

工具输出字段与 REST 成功响应一致。工具方法在调用 Feign 前验证 `city`，避免无效请求进入下游。

## 调用流程

1. 外部 AI Agent 连接 `http://localhost:8081/mcp` 并调用 `get_weather_by_city`。
2. Spring AI 根据 `@Tool`、`@ToolParam` 和 ToolCallbackProvider 暴露工具及其 JSON Schema。
3. 工具方法验证并去除输入首尾空白后调用 Feign Client。
4. Spring Cloud LoadBalancer 通过 Eureka 获取 `weather-service` 可用实例。
5. Feign 请求 `GET /api/weather/{city}`。
6. Weather Service 规范化城市别名，从内存数据中查找并返回天气。
7. MCP Server 将 Feign 响应转换为结构化工具结果并返回 Agent。

## 错误处理

| 场景 | Weather Service 行为 | MCP Server 行为 |
| --- | --- | --- |
| 空城市名 | `400 ProblemDetail` | 在 Feign 调用前拒绝并返回参数错误 |
| 未支持城市 | `404 ProblemDetail` | 返回“暂不支持城市：{city}” |
| Eureka 无可用实例 | 不适用 | 返回“天气服务暂时不可用，请稍后重试” |
| Feign 连接或读取超时 | 不适用 | 返回“天气服务暂时不可用，请稍后重试” |
| 未预期异常 | 记录服务端日志 | 返回通用工具错误，不泄露堆栈和内部地址 |

Feign 配置有限的连接超时和读取超时。示例不增加 fallback、熔断或自动重试，避免模拟数据掩盖真实的服务发现和故障行为。

## 配置

### Weather MCP Server

- 应用名：`weather-mcp-server`
- 默认端口：`8081`
- MCP 协议：显式配置 `spring.ai.mcp.server.protocol=STREAMABLE`
- MCP endpoint：使用默认 `/mcp`，并在配置中明确表达
- Eureka 地址：`${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}`

### Weather Service

- 应用名：`weather-service`
- 默认端口：`8082`
- Eureka 地址：`${EUREKA_DEFAULT_ZONE:http://localhost:8761/eureka/}`

两个应用都暴露 Actuator `health` 和 `info`。端口和 Eureka 地址允许通过环境变量或 Spring Boot 外部化配置覆盖。

## 安全边界

本示例不实现 MCP 认证鉴权。Spring AI 的 HTTP MCP Server 传输本身不会自动提供认证，因此 README 必须明确：`/mcp` 仅用于可信本地演示环境，不应直接暴露到生产公网。生产环境应在应用或上游网关增加认证、授权、TLS 和访问控制。

## 测试策略

### Weather Service 自动化测试

- 查询每个预置城市成功。
- 中文名称和英文别名映射正确。
- 英文别名大小写不敏感。
- 空白城市名触发输入错误。
- 未知城市触发业务未找到错误。
- MVC API 的 `200`、`400`、`404` 状态和响应结构正确。

### Weather MCP Server 自动化测试

- Mock Feign Client，验证成功调用返回结构化天气。
- 空城市名不会调用 Feign Client。
- 下游 `404` 转换为不支持城市的工具错误。
- 超时、连接失败和无实例异常转换为服务暂不可用错误。
- Spring ApplicationContext 中存在名为 `get_weather_by_city` 的 ToolCallback。
- MCP Server 配置为 Streamable HTTP，endpoint 为 `/mcp`。

测试 profile 禁用 Eureka 注册和注册表拉取，保证 `mvn verify` 不依赖外部基础设施。

### 手工验收

1. 启动已有 Eureka Server。
2. 启动 `weather-service`。
3. 启动 `weather-mcp-server`。
4. 确认 Eureka 中出现 `WEATHER-SERVICE` 和 `WEATHER-MCP-SERVER`。
5. 使用 MCP Inspector 或外部 AI Agent 连接 `http://localhost:8081/mcp`。
6. 调用 `get_weather_by_city`，参数为 `北京`，验证结构化天气结果。
7. 使用英文城市别名重复调用，验证映射结果。
8. 查询未知城市，验证工具返回可理解的错误。

## 验收标准

- 父工程执行 `mvn verify` 成功。
- 两个应用可以使用 Java 21 独立启动。
- 两个应用可以注册到默认或外部配置的 Eureka Server。
- MCP endpoint 使用 Streamable HTTP 在 `/mcp` 可用。
- 外部 MCP 客户端可以发现并调用 `get_weather_by_city`。
- 成功调用确实经由 Feign 和 Eureka 到达 Weather Service。
- 输入错误、未知城市和下游不可用均返回明确且不泄露内部实现的信息。
