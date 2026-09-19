# Spring AI Weather MCP Demo

This project demonstrates an external AI Agent calling a Spring AI MCP Server over Streamable HTTP. The MCP Server calls a separate Weather Service through OpenFeign, Spring Cloud LoadBalancer, and Eureka.

## Modules

- `weather-service` — simulated weather REST API on port `8082`
- `weather-mcp-server` — Streamable HTTP MCP Server on port `8081`
- `auth-server` — OAuth 2.1 / OIDC authorization server on port `8083`

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

Start the existing Eureka Server first. To call authenticated MCP endpoints, also start
`auth-server` (it needs MySQL and an external PKCS#12/JKS keystore — see
[auth-server/README.md](auth-server/README.md)):

```bash
mvn -pl auth-server spring-boot:run
```

Then start the weather service and the MCP server:

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

If `auth-server` runs under a different issuer (for example `https://auth.example.com`), pass the
same value to the MCP server so it validates the right issuer and JWKS:

```bash
AUTH_SERVER_URL=https://auth.example.com mvn -pl weather-mcp-server spring-boot:run
```

## Verify the Business API

```bash
curl http://localhost:8082/api/weather/%E5%8C%97%E4%BA%AC
```

Expected fields are `city`, `condition`, `temperatureCelsius`, `feelsLikeCelsius`, `humidityPercent`, and `windSpeedKph`.

Supported cities are 北京/Beijing, 上海/Shanghai, 广州/Guangzhou, 深圳/Shenzhen, and 杭州/Hangzhou.

## OAuth 2.1 认证（auth-server 签发，MCP Server 校验）

`weather-mcp-server` 只作为 OAuth 2.1 资源服务器：它不签发 token，也不提供登录页或授权端点，
只校验独立 `auth-server`（`8083`）签发的 JWT。MCP 规范要求的角色分离由此完成：

- 授权服务器（`auth-server`）：
  - Token：`POST http://localhost:8083/oauth2/token`
  - 授权：`GET http://localhost:8083/oauth2/authorize`
  - JWKS：`GET http://localhost:8083/oauth2/jwks`
  - 发现：`GET http://localhost:8083/.well-known/oauth-authorization-server`
- 受保护资源元数据（`weather-mcp-server`）：`GET http://localhost:8081/.well-known/oauth-protected-resource`

```json
{
  "resource": "http://localhost:8081/mcp",
  "authorization_servers": ["http://localhost:8083"]
}
```

`/mcp` 要求 `Authorization: Bearer <access token>`，token 必须包含 `weather:read` scope，且
audience 绑定到 `http://localhost:8081/mcp`；否则返回 `401`（scope 缺失返回 `403`）。

### 客户端（在 auth-server 注册）

| 客户端 | 流程 | 用途 |
| --- | --- | --- |
| `auth-machine` | client_credentials | 机器调用（本地 secret：`auth-machine-secret`） |
| `weather-mcp-inspector` | 授权码 + PKCE（公钥客户端，无 secret） | MCP Inspector 等交互式 MCP 客户端 |
| `auth-web-public` | 授权码 + PKCE + refresh_token | 示例 Web 客户端 |

### 用 client_credentials 取 token 并调用工具

```bash
ACCESS_TOKEN=$(curl -s -XPOST http://localhost:8083/oauth2/token \
  --user auth-machine:auth-machine-secret \
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
（使用的客户端为 `weather-mcp-inspector`，授权码 + PKCE，回调 `http://localhost:6274/oauth/callback`）。
auth-server 会依次展示登录页、组织选择页和授权确认页。

> 安全提醒：`auth-machine-secret` 等明文 secret 仅为本地演示。真实部署必须启用 TLS、使用密钥管理
> 服务、把 auth-server 的 issuer 换成公开 HTTPS 地址，并用 `AUTH_SERVER_URL` 让 MCP Server 校验
> 同一个 issuer/JWKS。

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

## auth-server（独立 OAuth 2.1 / OIDC 授权服务器）

`auth-server` 是独立的授权服务器：基于现有 `sys_*` 只读表做 BCrypt 认证与组织级角色绑定，
用 Flyway 管理自己的 `oauth2_*` 表，支持 Authorization Code + PKCE、Refresh Token 轮换、
Client Credentials、OIDC Discovery / ID Token / UserInfo / JWKS，签名密钥来自外部
PKCS#12/JKS。

- 模块说明、配置与本地运行步骤：[auth-server/README.md](auth-server/README.md)
- 测试、smoke 脚本与验收步骤：[docs/auth-server-testing.md](docs/auth-server-testing.md)

快速验收（服务已在 `8083` 启动）：

```bash
scripts/auth-server-smoke.sh
```

## Security Notice

This demo enables MCP OAuth 2.1 authentication on `/mcp` (see above) but does **not**
enable TLS in the demo profile. Do not expose `/mcp` directly to the public internet
without TLS. The MCP server only validates tokens issued by `auth-server`; a production
deployment must enable TLS, externalize the auth-server signing key and client secrets,
keep `AUTH_ISSUER` (auth-server) and `AUTH_SERVER_URL` (MCP server) in sync, and add
network access controls in the application or an upstream gateway.
