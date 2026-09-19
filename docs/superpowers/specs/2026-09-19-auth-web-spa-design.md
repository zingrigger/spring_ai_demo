# Auth Web 前后端分离（Vue 3 SPA）设计

> 日期：2026-09-19  
> 状态：待评审  
> 范围：只改造 `auth-server` 的 Web UI 层（登录 / 组织选择 / 授权确认 / 落地页）与前端构建；
> OAuth 2.1 / OIDC 协议行为、令牌格式、数据库 schema、`weather-mcp-server` 均不变

## 1. 目标与边界

把 auth-server 现有的 Thymeleaf 服务端渲染页面改为前后端分离的 Vue 3 SPA：
前端是独立工程 `auth-web/`，通过 JSON API 与 auth-server 交互；构建产物默认打进 auth-server
的可执行 jar，也可以单独交给 Nginx 做同源反向代理。

本期包含：

- 新增独立前端工程 `auth-web/`：Vue 3 + TypeScript + Vite + Vue Router + Tailwind CSS v4 + vue-i18n。
- 登录、组织选择、授权确认、已登录落地页四页 SPA，视觉方向为「品牌分栏」（见第 5 节）。
- auth-server 新增 JSON API（`/api/**`）驱动上述页面；consent 的最终提交仍为原生表单 POST。
- Maven 集成：`frontend-maven-plugin` 构建前端并把 `dist` 打进 jar；`-DskipFrontend=true` 可跳过。
- 移除 Thymeleaf 依赖、三个模板、仅用于渲染的 controller。
- 测试与文档同步更新。

本期不包含：

- 深色模式、主题定制、品牌资源上传。
- consent 页的「拒绝」按钮（保持现状：只有同意路径，不新增 `access_denied` 交互）。
- 账户中心、会话管理、已授权客户端管理等新功能。
- 浏览器端到端测试（项目没有 e2e 基建）；不做 Playwright/Cypress。
- Nginx 生产部署自动化：只提供等价示例配置与说明。
- 其他语言（只做中 / 英）。

## 2. 关键决策与依据

**决策 1：代码分离、部署同源。** 前端是独立工程，但必须与 auth-server 同源部署。

- Spring Authorization Server 的 `consentPage()` 是「授权请求流程中重定向用户去的 URI」，
  未登录时由 `LoginUrlAuthenticationEntryPoint("/login")` 把浏览器带回登录页，整条链路依赖
  auth-server 自己的 session cookie 与 302 跳转（[protocol-endpoints](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html)）。
- 跨域部署会让 auth-server 的 session cookie 变成第三方 cookie：Safari / Firefox 默认拦截，
  IETF 的浏览器应用 BCP 把第三方 cookie 拦截列为必考因素，并强烈推荐 BFF 架构
  （[draft-ietf-oauth-browser-based-apps](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps)）。
- 业界同类系统同样把登录 UI 放在授权服务器 origin：Keycloak 由自身渲染；用 React 写 Keycloak
  登录页的主流方案 Keycloakify 会把前端构建成 JAR 部署进 Keycloak（[docs.keycloakify.dev](https://docs.keycloakify.dev)）；
  Auth0 / Okta 的 Universal Login 也托管在 IdP 域，Auth0 官方理由即为
  *"Users are not redirected to a third-party site"*（[custom-domains](https://auth0.com/docs/customize/custom-domains)）。

**决策 2：登录/组织流程用 JSON API 驱动，consent 提交保留原生表单 POST。**

- 登录、组织选择是纯应用逻辑，JSON API 比表单跳转更适合 SPA，也让 `/login`、`/organizations`
  在反向代理里成为纯前端路由。
- consent 的「同意」触发授权码签发并把浏览器 302 回客户端的 `redirect_uri`，必须是浏览器顶层
  表单 POST（`POST /oauth2/authorize`），不能由 fetch 代劳。
- consent 页路由从 `/oauth2/consent` 改为 `/consent`，使 `/oauth2/*` 在反向代理中保持「纯后端」。

**决策 3：构建进 jar 为默认，Nginx 同源反代为可选部署。** 两种形态浏览器视角都是同源，
Cookie / CSRF 复杂度相同；演示环境只需一个 jar，生产环境可换成 Nginx 托管 `dist`。

## 3. 架构与部署形态

```text
浏览器（同一 origin）
  ├── SPA 路由  /  /login  /organizations  /consent   → index.html + /assets/**（静态资源）
  └── 后端路径                                        → auth-server（8083）
        ├── /api/auth/session|login|logout    JSON
        ├── /api/organizations                JSON
        ├── /api/consent                      JSON
        ├── /oauth2/authorize|token|jwks|…    协议端点（不变）
        └── /.well-known/**  /userinfo  /actuator   不变
```

- **默认（jar）**：`auth-server` 打包 `auth-web/dist` 到 `static/`，服务端提供 SPA fallback；
  启动一个进程即为完整系统。
- **可选（Nginx）**：`/` → dist（`try_files $uri /index.html`），
  `/api/`、`/oauth2/`、`/.well-known/`、`/userinfo`、`/actuator/` → `8083`。
- **开发**：Vite dev server（5173）proxy 上述后端前缀到 `http://localhost:8083`，浏览器视角仍是同源。

## 4. 交互流程

```text
1  MCP 客户端      浏览器访问 GET /oauth2/authorize?client_id=…&code_challenge=…
2  auth-server     未登录 → 请求存入 session（saved request）→ 302 /login
3  SPA             GET /api/auth/session → {authenticated:false}
4  SPA → 后端      POST /api/auth/login → 200 {next:"/organizations"}
5  SPA             GET /api/organizations；单组织时服务端直接绑定并返回 {next}
6  SPA → 后端      POST /api/organizations {orgId} → 校验归属 → 200 {next:"<saved request>"}
7  SPA             顶层导航到 next（回到 /oauth2/authorize?…）
8  auth-server     已登录 + 已绑定 + 未同意过 → 302 /consent?client_id=…&scope=…&state=…
                   已同意过 → 跳过 consent，直接签发 code
9  SPA             GET /api/consent?… → 客户端名称、scopes、组织、用户
10 SPA             用户勾选 scope 后提交原生表单 POST /oauth2/authorize（含 _csrf）
11 auth-server     302 redirect_uri?code=…&state=…
12 MCP 客户端      POST /oauth2/token + PKCE verifier → access / refresh token
```

要点：

- 登录态是 auth-server 的 session（`JSESSIONID`，HttpOnly），SPA 不接触任何 token。
- `next` 一律由服务端计算：saved request URL 优先，其次 `/organizations`（登录后）或 `/`（绑定组织后）。
- `OrganizationBindingFilter`、saved request 机制、`RequestCache` 均保留不变。
- 会话过期：API 返回 401 → SPA 跳回 `/login`。

## 5. 前端应用

### 视觉方向（已确认：品牌分栏）

- 左侧品牌区（约 40%）：`bg-gradient-to-br from-indigo-600 via-violet-600 to-purple-500`，
  白色产品名与一句 slogan（随语言切换）。
- 右侧内容区：白底，垂直居中的表单卡片；`rounded-xl`、`border-slate-200`、
  输入框 `focus:ring-2 ring-indigo-500`、主按钮 `bg-indigo-600 hover:bg-indigo-500`。
- 错误提示：`bg-red-50 text-red-700 border border-red-200` 的提示条，出现在表单上方。
- 右上角固定语言切换（`中文 / EN`），默认语言按 `navigator.language` 选择、中文兜底，
  用户选择写入 `localStorage`（键 `auth-web.locale`）。
- 响应式：`< md` 断点隐藏品牌分栏，表单居中占满宽度。
- 不做深色模式（本期范围外）。

### 路由与视图

| 路由 | 视图 | 说明 |
| --- | --- | --- |
| `/login` | `LoginView` | 账号 + 密码；401 显示表单内错误 |
| `/organizations` | `OrganizationsView` | 单选组织列表 + 继续；单组织时后端已绑定，直接跳转 |
| `/consent` | `ConsentView` | 客户端名 + 组织上下文 + 可勾选 scope + 同意 |
| `/` | `HomeView` | 已登录落地页：账号、组织、退出登录 |

- 路由守卫：进入任意路由先取 `GET /api/auth/session`，按固定优先级判断：
  1) 未登录 → `/login`；2) 已登录但未绑定组织 → `/organizations`；
  3) 已登录且已绑定、存在 `pending` → 顶层导航到该 URL（仅在 `/` 与 `/login` 两个路由上应用，
     避免打断 `/organizations`、`/consent` 上正在进行的步骤）。
- scope 展示名放在 i18n（`profile` → 读取基本资料、`weather:read` → 读取天气数据），未知 scope 显示原始值。
- consent 表单：Vue 动态构造隐藏 `<form method="post" action="/oauth2/authorize">`，
  字段为 `client_id`、`state`、重复的 `scope`、`_csrf`，然后 `submit()`。

### 文件布局

```text
auth-web/
  package.json  package-lock.json  vite.config.ts  tsconfig.json  index.html
  src/
    main.ts  App.vue  router.ts
    api/client.ts        # fetch 封装：JSON、X-XSRF-TOKEN、错误映射
    api/auth.ts          # session / login / logout / organizations / consent
    views/{LoginView,OrganizationsView,ConsentView,HomeView}.vue
    components/{AuthLayout,FormField,AlertBanner,LocaleSwitch}.vue
    i18n/{index.ts,zh.ts,en.ts}
    assets/main.css      # Tailwind v4 入口
  tests/                 # Vitest + @vue/test-utils
```

## 6. 后端 API 契约

| 方法 | 路径 | 认证 | 请求 | 响应 |
| --- | --- | --- | --- | --- |
| GET | `/api/auth/session` | 公开 | – | `{authenticated, user?, organization?, pending?}` |
| POST | `/api/auth/login` | 公开 + CSRF | `{account, password}` | `200 {next}` / `401 {error:"invalid_credentials"}` |
| POST | `/api/auth/logout` | 已登录 + CSRF | – | `204` |
| GET | `/api/organizations` | 已登录 | – | `200 {organizations:[{id,name}], next: string\|null}`（`next` 非空 = 单组织已自动绑定）；无组织 `403 {error:"access_denied"}` |
| POST | `/api/organizations` | 已登录 + CSRF | `{orgId}` | `200 {next}`；越权 `403 {error:"access_denied"}` |
| GET | `/api/consent` | 已登录 | `?client_id&scope&state` | `{clientId, clientName, scopes:[…], state, organization, user}` |
| POST | `/oauth2/authorize` | session + CSRF | 表单：`client_id, state, scope[], _csrf` | `302` 回客户端 `redirect_uri` |

- `GET /api/organizations` 在用户只有一个组织时由服务端直接绑定并返回 `next`，不显示选择页
  （保持现有行为）。
- `POST /api/auth/logout` 由应用实现：失效当前 session、清理 `SecurityContext` 后返回 `204`；
  同时禁用 Spring Security 默认的 `/logout` 过滤器，避免出现两个登出端点。
- `state` 可能为空；`scope` 参数可能重复出现，服务端按现有逻辑拆分去重并过滤 `openid`。
- 未登录访问 `/api/**` 返回 `401` JSON；浏览器直接访问受保护的 HTML 路由时仍 302 到 `/login`。

## 7. 安全设计

- **CSRF**：`CookieCsrfTokenRepository.withHttpOnlyFalse()` + 显式的明文
  `CsrfTokenRequestAttributeHandler`（不用 XOR 处理器）。服务端不再渲染含 token 的 HTML，
  BREACH 不适用；明文处理器保证同一个 cookie 值既能做 `X-XSRF-TOKEN` 请求头，也能做 consent
  表单的 `_csrf` 字段。`GET /api/auth/session` 主动解析一次 token，确保首个响应就下发 cookie。
- **Cookie**：`JSESSIONID` 维持 HttpOnly + SameSite=Lax；`XSRF-TOKEN` JS 可读、SameSite=Lax；
  文档注明生产环境必须启用 `Secure` 与 TLS。
- **会话固定**：登录改为自定义 JSON 端点后，成功时显式执行 session id 变更
  （`SessionAuthenticationStrategy`），不依赖 `formLogin` 的默认行为。
- **CORS**：不添加任何 CORS 配置；同源是设计前提。
- **错误语义**：登录失败统一 `401` + 同一文案，不区分账号不存在与密码错误；无组织维持 403。
- **公开与受保护**：SPA 外壳（`/`、`/login`、`/organizations`、`/consent`、`/assets/**`、
  `/favicon.ico`、`/api/auth/session`、`/api/auth/login`）permitAll；数据接口与协议端点按现有策略鉴权。
- 移除的攻击面：`/login?error`、`?logout` 参数、Thymeleaf 模板渲染。

## 8. 构建与开发流程

- `auth-server/pom.xml`：
  - 移除 `spring-boot-starter-thymeleaf`。
  - 新增 `frontend-maven-plugin`（固定 Node 版本，支持 `NODE_DOWNLOAD_ROOT` 镜像）执行
    `npm ci` + `npm run build`，工作目录 `../auth-web`。
  - 资源拷贝插件把 `../auth-web/dist` 复制到 `${project.build.outputDirectory}/static`，
    绑定 `generate-resources` 阶段（在 frontend 插件之后执行）。
  - `-DskipFrontend=true` 同时跳过前端构建与资源拷贝，供纯后端迭代与离线测试。
- **SPA fallback**：服务端把 `/`、`/login`、`/organizations`、`/consent` 转发到 `index.html`；
  `index.html` 响应 `Cache-Control: no-cache`，`/assets/**`（Vite 带内容 hash）使用长缓存。
- **开发**：`cd auth-web && npm run dev` + `mvn -pl auth-server spring-boot:run`；
  Vite proxy `/api`、`/oauth2`、`/.well-known`、`/userinfo`、`/actuator` → `8083`。
- **Nginx 示例**（写入文档）：

  ```nginx
  root /srv/auth-web/dist;
  location / { try_files $uri /index.html; }
  location ~ ^/(api|oauth2|\.well-known|userinfo|actuator)/ { proxy_pass http://127.0.0.1:8083; }
  ```

- **.gitignore**：新增 `auth-web/node_modules`、`auth-web/dist`、`.superpowers/`。
- `auth-server/README.md`、根 `README.md`、`docs/auth-server-testing.md` 更新为新的页面与构建方式。

## 9. 测试策略

后端（MockMvc，改造现有测试）：

- 流程辅助类 `AuthorizationCodeFlow` 改为：JSON 登录 → 组织 API → consent 数据 API →
  `with(csrf())` 表单 POST `/oauth2/authorize`；PKCE、redirect_uri 校验、refresh 轮换、
  token 生命周期等断言全部保留。
- 新增 API 测试：登录成功 / 失败 401、session 匿名 / 已登录 / 已绑定三态、组织列表
  （多组织 / 单组织自动绑定）、越权选组织 403、consent 数据、缺少 CSRF 403。
- 前端产物测试：`GET /login` 返回 `index.html`；标 `@Tag("frontend")`，
  Maven 在 `-DskipFrontend=true` 时通过 `excludedGroups` 跳过该 Tag。
- `scripts/auth-server-smoke.sh` 不受影响，继续作为机器端点验收。

前端（Vitest + @vue/test-utils）：

- `api/client.ts`：请求头、`X-XSRF-TOKEN`、401/403 错误映射。
- 路由守卫：三种 session 状态 → 目标路由。
- consent 表单构造：字段与重复 scope 的顺序。
- 不做浏览器 e2e。

手动验收（写入 `docs/auth-server-testing.md`）：MCP Inspector 走完整浏览器流程
（登录 → 组织 → 同意 → 拿 token → 调用 `/mcp` 工具），并检查 Language 切换与窄屏布局。

## 10. 迁移与删除清单

| 动作 | 目标 |
| --- | --- |
| 删除 | `auth-server/src/main/resources/templates/{login,consent,organizations}.html` |
| 删除 | `LoginController`（仅渲染视图） |
| 改写 | `ConsentController` → JSON `GET /api/consent` |
| 改写 | `OrganizationController` → JSON `/api/organizations` |
| 新增 | `AuthApiController`（session / login / logout）、SPA fallback 路由 |
| 修改 | `SecurityConfig`：移除 `formLogin` 与默认 `/logout`，加 CSRF cookie 仓库与处理器、公开 SPA 外壳、401/302 入口点分流 |
| 修改 | `AuthorizationServerConfig`：`consentPage("/consent")` |
| 修改 | `auth-server/pom.xml`、`.gitignore`、README、测试文档 |

## 11. 验收标准

1. `mvn -pl auth-server verify` 通过（含前端构建）；`-DskipFrontend=true` 亦通过。
2. 启动 `8083` 后：访问 `/login` 得到 Vue SPA；MCP Inspector 完整流程可走通并成功调用受保护工具。
3. 刷新 `/login`、`/organizations`、`/consent`、`/` 任意路由不出现 404；jar 形态按本节验收，
   文档中的 Nginx 示例配置做一次本地手工验证（不进入自动化测试）。
4. 中英文切换生效并持久化；窄屏下品牌分栏折叠。
5. 浏览器 Network 面板中所有请求同源，无 CORS 预检；缺少 CSRF 的写请求返回 403。
6. 仓库中不再存在 Thymeleaf 依赖与模板；`scripts/auth-server-smoke.sh` 通过。
