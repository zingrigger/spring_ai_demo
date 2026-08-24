# WeatherTool 测试说明

本文说明如何测试 `weather-mcp-server` 中的 `get_weather_by_city` 工具。

## 测试前提

需要 Java 21、Maven 3.9+，以及一个运行中的 Eureka Server。默认 Eureka 地址为：

```text
http://localhost:8761/eureka/
```

如果 Eureka 地址不同，启动两个应用时设置 `EUREKA_DEFAULT_ZONE`。

## 1. 不接入 AI Agent：直接测试 WeatherTool

### 1.1 运行自动化测试

自动化测试使用 Mock Feign Client，不需要启动 Eureka、Weather Service 或 MCP Server：

```bash
mvn -pl weather-mcp-server test
```

测试覆盖：

- 正常查询并转换天气结果
- 空城市参数
- 未支持城市
- Weather Service 不可用
- Feign 超时
- 下游异常和响应转换异常

### 1.2 直接测试 Weather Service REST API

启动 Weather Service：

```bash
mvn -pl weather-service spring-boot:run
```

查询北京天气：

```bash
curl --fail --silent http://localhost:8082/api/weather/%E5%8C%97%E4%BA%AC
```

预期返回：

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

未知城市应返回 `404`：

```bash
curl --include http://localhost:8082/api/weather/Atlantis
```

### 1.3 使用 MCP Inspector 测试 WeatherTool

先启动 Eureka、Weather Service 和 MCP Server：

```bash
mvn -pl weather-service spring-boot:run
```

```bash
mvn -pl weather-mcp-server spring-boot:run
```

启动 MCP Inspector：

```bash
npx @modelcontextprotocol/inspector
```

在 Inspector 中选择 `Streamable HTTP`，填入：

```text
http://localhost:8081/mcp
```

连接后确认工具列表中存在 `get_weather_by_city`，然后使用参数调用：

```json
{
  "city": "北京"
}
```

也可以使用英文别名：

```json
{
  "city": "Shanghai"
}
```

### 1.4 不使用 Inspector，直接用 curl 测试 MCP 协议

先初始化 MCP 会话并保存返回的 `Mcp-Session-Id`：

```bash
curl --include --silent --show-error \
  -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": {"name": "curl", "version": "1.0"}
    }
  }'
```

复制响应头中的 `Mcp-Session-Id`，替换下面命令里的 `<SESSION_ID>`：

```bash
curl --silent --show-error \
  -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <SESSION_ID>' \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }'
```

调用 WeatherTool：

```bash
curl --silent --show-error \
  -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: <SESSION_ID>' \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_weather_by_city",
      "arguments": {"city": "北京"}
    }
  }'
```

成功响应中的 `isError` 应为 `false`，`content[0].text` 是结构化天气 JSON。

## 2. 接入外部 AI Agent 测试

本仓库不实现 AI Agent 或 Spring AI MCP Client。外部 Agent 只需要支持 MCP Streamable HTTP，并配置 MCP Server 地址：

```text
http://localhost:8081/mcp
```

### 2.1 启动服务

按以下顺序启动：

1. Eureka Server：`http://localhost:8761`
2. `weather-service`：`http://localhost:8082`
3. `weather-mcp-server`：`http://localhost:8081`

确认 Eureka 中存在：

- `WEATHER-SERVICE`
- `WEATHER-MCP-SERVER`

### 2.2 在 Agent 中配置 MCP Server

下面的配置都使用同一个 MCP 地址：

```text
http://localhost:8081/mcp
```

不要把地址填写成 Weather Service 的 REST 地址 `http://localhost:8082/api/weather`；AI Agent 只能通过 MCP endpoint 发现和调用 WeatherTool。

#### 1. Codex

Codex CLI 使用 TOML 配置。编辑全局配置 `~/.codex/config.toml`，或在受信任的项目中使用 `.codex/config.toml`：

```toml
[mcp_servers.weather_mcp]
url = "http://localhost:8081/mcp"
enabled = true
```

然后查看 MCP Server 状态：

```bash
codex mcp list
```

重新启动 Codex 后，在对话中输入：

```text
请查询北京当前天气。
```

配置说明参考 [OpenAI Codex MCP 文档](https://developers.openai.com/codex/mcp/)。

#### 2. Claude Code

使用 `http` 传输添加远程 Streamable HTTP Server：

```bash
claude mcp add --transport http weather-mcp http://localhost:8081/mcp
claude mcp list
```

也可以在项目根目录创建 `.mcp.json`：

```json
{
  "mcpServers": {
    "weather-mcp": {
      "type": "http",
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

Claude Code 文档中 `streamable-http` 是 `http` 的别名；这里使用 `http` 以兼容当前 CLI 命令。配置说明参考 [Claude Code MCP 文档](https://code.claude.com/docs/en/mcp)。

#### 3. Pi Agent

Pi Agent 当前核心版本明确不原生支持 MCP，因此没有可直接填写的 MCP Server 配置项，也不能仅通过 `settings.json` 连接本项目的 `/mcp` endpoint。

如果使用了提供 MCP 能力的第三方 Pi extension 或 package，需要按照该 extension/package 的文档配置远程 URL：

```text
http://localhost:8081/mcp
```

如果没有安装此类扩展，请使用本说明前面的 MCP Inspector、Codex、Claude Code 或 OpenCode 进行 MCP 测试。Pi Agent 的核心定位和“不内置 MCP”的说明见 [Pi coding agent README](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent)。

#### 4. OpenCode

在项目根目录创建 `opencode.json`（也可以放到 OpenCode 全局配置目录）：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "weather-mcp": {
      "type": "remote",
      "url": "http://localhost:8081/mcp",
      "enabled": true
    }
  }
}
```

启动或重新载入 OpenCode 后，确认 MCP 工具列表中出现 `get_weather_by_city`，再发送：

```text
请查询上海当前天气。
```

配置说明参考 [OpenCode MCP 文档](https://opencode.ai/docs/mcp-servers/)。

### 2.3 使用自然语言验证

连接成功后，可以向 Agent 发送：

```text
请查询北京当前天气。
```

Agent 应发现并调用 `get_weather_by_city`，最终返回北京的模拟天气。

再测试英文别名：

```text
请查询 Shanghai 当前天气，并告诉我温度和湿度。
```

再测试错误场景：

```text
请查询 Atlantis 当前天气。
```

Agent 应能理解 WeatherTool 返回的“不支持城市”错误，而不是暴露 Feign 堆栈或内部服务地址。

### 2.4 故障排查

| 现象 | 检查项 |
| --- | --- |
| Agent 无法连接 MCP | 确认 `weather-mcp-server` 已启动，地址是 `http://localhost:8081/mcp` |
| 工具列表为空 | 查看 MCP Server 启动日志是否包含 `Registered tools: 1` |
| 工具返回服务不可用 | 确认 `weather-service` 已启动且 Eureka 中为 `UP` |
| Weather Service 直接可用但工具失败 | 检查 Eureka 服务名是否为 `WEATHER-SERVICE`，以及 Feign 配置的服务名为 `weather-service` |
| 中文城市查询失败 | 先用 `curl` 验证 URL 编码，再使用 MCP Inspector 或 Agent 的结构化参数调用 |
