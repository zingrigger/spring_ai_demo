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
