# 不兼容变更（Breaking Changes）

> [English Version](en/breaking-changes.md)

> 本文件是 **2.7.x 线的累积台账**：任何 2.7.x 版本引入不兼容变更，都在文末**新增一节**，
> 并在上面的版本索引里登记一行。每个版本的**全量**条目（含新特性与修复）见 `CHANGELOG.md`。
>
> - **收录范围**：升级会让使用者**必须动手**的条目 —— 移除、改名、签名或可见性变更、语义收紧；新特性与常规修复不收。
> - **登记要求**：每条要么给出**迁移动作**，要么说明**为何无需迁移**。
> - **版本号与日期**以 `CHANGELOG.md` 为准；节标题带版本号，引用请用版本号描述，不要依赖中文序号（序号会随版本重排）。

## 版本索引

| 版本 | 日期 | 主题 |
|------|------|------|
| **2.7.6** | 2026-09-10 | 模块拆分（`spring-web-support` → `spring-web-servlet` + `spring-web-mvc-support`）、`redirect:` 语义收紧、视图解析失败不再静默、生产级 Netty / 线程池默认值 |
| **2.7.4** | 2026-08-06 | `JsonConverter` 接口新增抽象方法 `toJsonBytes(Object)` |

---

## [2.7.6] - 2026-09-10

### 一、模块拆分：`spring-web-support` 一分为二

**变更**：`spring-web-support` 不再存在，拆分为

- `spring-web-servlet`：纯 Servlet 桥接（Filter / Servlet / `HttpSession` / JSP / `@SessionAttribute` 等官方 `spring-web` 语义），**零 SpringMVC 依赖**；
- `spring-web-mvc-support`：SpringMVC 兼容桥接（`org.springframework.web.servlet.*` 重写类、`WebMvcConfigurer` / `HandlerInterceptor` / `RequestBodyAdvice` 桥接），**依赖 `spring-web-servlet`**。

starter 的自动配置同步拆为 `SpringWebServletAutoConfiguration` + `SpringWebMvcSupportAutoConfiguration`（按各自模块的 class 条件生效）。

**影响**：直接依赖 `spring-web-support` 的项目无法解析该坐标。

**迁移**：按需要替换坐标 —— 只需要 Servlet 语义（Filter / Session / JSP）时用 `spring-web-servlet`；
继续使用 `org.springframework.web.servlet.*` 重写类或 `WebMvcConfigurer` 时用 `spring-web-mvc-support`。
只依赖 `spring-boot-starter-web`（本框架的 starter）的项目**无需改动**。

### 二、`redirect:` 语义收紧

**变更**：`redirect:` 前缀只在**视图方法**上生效。`@ResponseBody` / `@RestController` 返回字符串
`"redirect:/x"` 不再被劫持为 302，而是按普通字符串序列化；目标 URL 已含 query 时用 `&` 续接而非重复 `?`。

**影响**：此前依赖「返回字符串即 302」写法的接口行为改变 —— JSON 响应体变成 `"redirect:/x"` 字符串。

**迁移**：需要跳转时显式返回状态与 `Location`：

```java
return ResponseEntity.status(302).location(URI.create("/target?a=1")).build();
```

### 三、视图解析失败不再静默

**变更**：无法解析的视图名由「空响应 200」改为抛 `IllegalArgumentException`（500 + 错误上下文），
`ViewResolver` 的解析异常同时记录日志。

**影响**：视图名拼错、模板引擎未引入、前缀配置错误等此前表现为 200 空体，现在表现为 500。

**迁移**：核对 `spring.web.view.*` 前缀 / 后缀与已引入的模板引擎；如确实需要「找不到就空响应」，
显式注册兜底 `ViewResolver`。

### 四、静态资源只接受 GET

**变更**：`ResourceRequestHandler` 仅允许 GET。HEAD 由路由层自动映射到 GET，但只回元数据、**跳过文件 IO**。

**影响**：对静态资源路径发起的其他方法不再由静态资源处理器响应（按常规路由未命中处理）。

**迁移**：不要把静态资源路径当作 POST/PUT 的目标。

### 五、生产级 Netty 与线程池默认值

**变更**：以下默认值由「开发友好」调整为「生产友好」：

| 配置键 | 旧默认 | 新默认 | 说明 |
|--------|--------|--------|------|
| `server.netty.so-backlog` | 128 | **1024** | 连接建立队列，避免突发握手被丢弃 |
| `server.netty.so-keepalive` | false | **true** | 开启 TCP keepalive，回收对端猝死的连接 |
| `server.http.max-content-length` | 1MB | **4MB** | 单请求体上限 |
| `pool.queue-capacity` | 无界 | **100**（有界） | 有界队列使 `pool.max-pool-size` 扩容与「队列满 → 503 快速失败」真正生效 |

**影响**：请求体上限放宽 4 倍；线程池由无界排队（请求无限堆积、延迟上升）改为有界排队（超限直接 503）。

**迁移**：

- 依赖「无界队列削峰」的应用请显式设置 `pool.queue-capacity`（或调大 `pool.max-pool-size`），并确认能接受 503 语义；
- 需要更严格的请求体上限时显式设置 `server.http.max-content-length`。

---

## [2.7.4] - 2026-08-06

### `JsonConverter` 接口新增抽象方法 `toJsonBytes(Object)`

**变更**：`io.springperf.web.json.JsonConverter` 新增 `byte[] toJsonBytes(Object obj)`，
用于跳过中间 `String` 拷贝直接输出字节；框架自带的 Jackson / Fastjson 转换器已实现。

**影响**：自行实现 `JsonConverter` 的外部实现类（含匿名类）会因未实现该抽象方法而**编译失败 / 运行期 `AbstractMethodError`**。

**迁移**：补上实现，委托既有序列化即可：

```java
@Override
public byte[] toJsonBytes(Object obj) {
    return toJson(obj).getBytes(StandardCharsets.UTF_8);
}
```
