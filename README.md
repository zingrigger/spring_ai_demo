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

> 注意：默认密钥仅用于本地演示，任何真实部署都必须通过 USER_CONTEXT_JWT_SECRET 覆盖。

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

This demo does not implement MCP authentication. Do not expose `/mcp` directly to the public internet. A production deployment must add TLS, authentication, authorization, and network access controls in the application or an upstream gateway.
