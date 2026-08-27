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
> 并用启动参数覆盖密钥（保留 `{noop}` 前缀），例如
> `--spring.security.oauth2.authorizationserver.client.weather-mcp-machine.registration.client-secret='{noop}<实际密钥>'`
> （注意：带连字符的键名不能通过环境变量覆盖——Spring 的宽松绑定会丢弃连字符），
> 或改用密钥管理服务 / 外部授权服务器（届时 MCP Server 仅保留资源服务器角色）。

## Connect an MCP Client

完整测试步骤（包括不接入 Agent 的直连测试和接入外部 Agent 的测试）见 [WeatherTool 测试说明](docs/weather-tool-testing.md)。

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

This demo enables MCP OAuth 2.1 authentication on `/mcp` (see above) but does **not**
enable TLS in the demo profile. Do not expose `/mcp` directly to the public internet
without TLS. A production deployment must enable TLS, externalize the OAuth client
secret (or replace the embedded authorization server with an external IdP), and add
network access controls in the application or an upstream gateway.
