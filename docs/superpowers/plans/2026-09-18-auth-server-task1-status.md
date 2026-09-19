# Auth Server Task 1 状态记录

记录时间：2026-09-19

## 已完成

- 根 `pom.xml` 已加入 `auth-server` 模块。
- 已创建 `auth-server/pom.xml`，声明 Web、Security、Authorization Server、Resource Server、JDBC、Thymeleaf、Flyway、MySQL 和测试依赖。
- 已创建 `AuthServerApplication`。
- 已创建 `application.yml`，包含默认端口、应用名、数据源、issuer、外部 keystore 和 token 时长配置。
- 已创建 `AuthServerApplicationTest`；测试上下文仅排除数据库与 Flyway 自动配置，避免 Task 1 依赖真实数据库。
- 已记录 TDD RED：模块不存在时 `mvn -pl auth-server -DskipTests=false test` 按预期失败。

## Task 1 尚未完成

- 尚未取得 `mvn -pl auth-server test` 的 GREEN 结果。此前因 Maven 本地缓存写权限/审批服务故障而中断。
- 尚未在当前改动上运行完整 `mvn test`。改动前基线中，现有 `weather-service` 的 `WeatherControllerTest` 因 JDK 23 下 Mockito/Byte Buddy 无法 self-attach 而失败。
- 尚未执行 Task 1 的独立规格符合性与代码质量审查。
- 尚未确认 Spring Boot 4.0.8 下所选 Authorization Server starter 与 JDBC/Flyway 依赖可完整解析、编译并启动。
- `application.yml` 当前仅配置 access/refresh TTL，且默认值为 15 分钟/7 天；尚未补齐并对齐规格中的 authorization code 5 分钟、access token 10 分钟、ID token 10 分钟、refresh token 30 天和 session idle 30 分钟。
- 原计划要求的 `feat: scaffold auth server module` 完成态提交未产生；当前成果将作为 WIP 合并，不代表 Task 1 验收通过。

## 后续恢复入口

1. 运行 `mvn -pl auth-server test`，修复依赖、编译或启动问题，直到通过。
2. 运行 `mvn test`，区分 auth-server 回归与已记录的 Mockito/JDK 23 基线问题。
3. 对 Task 1 差异执行规格符合性和代码质量审查；修复所有阻塞项并复审。
4. 审查通过后，才将 Task 1 标记为完成并继续 Task 2。
