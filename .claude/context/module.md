# 模块架构

## 5 个模块

```
spring-web-parent
│  Spring Boot 2.6.15 parent, Java 8
│
├── spring-web                     核心框架
├── spring-web-support             可选 Servlet/SpringMVC 桥接层
├── spring-boot-starter-web        Spring Boot 自动配置
├── spring-web-test                核心框架的示例/E2E测试应用
└── spring-web-support-test        桥接层的示例/E2E测试应用
```

---

## spring-web（核心框架）

```

spring-web
├── context/                     组件容器 + 生命周期
│   ├── WebComponent             所有框架组件的根接口（extends Ordered）
│   ├── LifecycleWebComponent    带三阶段生命周期的组件接口
│   ├── BaseWebComponent         持有 WebContext 的抽象基类，建议自定义组件继承
│   ├── WebComponentContainer    组件注册表基类（Map<String,WebComponent>）
│   │   ├── registerWebComponent()
│   │   ├── getWebComponent(Class)
│   │   ├── getWebComponentWithDefault(Class, default)
│   │   └── autoRegisterWebComponent(Class, FactoryFunction)
│   ├── WebContext               应用上下文（extends WebComponentContainer）
│   │   ├── 持有 Spring ApplicationContext
│   │   ├── 持有 DispatcherHandler
│   │   ├── 持有 ApplicationProperties
│   │   └── implements InitializingBean → 驱动整个组件生命周期
│   ├── ApplicationProperties    类型安全的配置属性访问（包装 Environment）
│   ├── PropertiesConstant       配置键常量
│   │   ├── SERVER_PORT, CONTEXT_PATH
│   │   ├── HTTP_MAX_CONTENT_LENGTH, HTTP_TIMEOUT
│   │   └── CHECK_ON_STARTUP
│   └── WebComponentWrapper      将任意对象包装为 WebComponent（如 Spring 的 HttpMessageConverter）
│
├── server/                      Netty 服务器
│   ├── HttpHandler              @FunctionalInterface，顶层 HTTP 处理策略
│   ├── NettyHttpHandler         Netty ChannelHandler，适配 Netty → 框架请求/响应
│   ├── NettyHttpServer          implements SmartLifecycle，启动/停止 Netty
│   │   ├── 管线: HttpServerCodec → ChunkedWriteHandler
│   │   │     → SupportMultipartAggregator → BackpressureHandler → NettyHttpHandler
│   │   └── 可选 SSL 支持
│
├── http/                        HTTP 请求/响应抽象
│   ├── WebServerHttpRequest     统一请求接口（extends ServerHttpRequest + BodyHttpInputMessage）
│   │   ├── getPath(), getParameterMap(), getMultiFileMap(), getPartMap()
│   │   ├── getCharacterEncoding(), getLocales()
│   │   ├── getWebContext(), getRequestContext()
│   │   └── acquire() / release() 引用计数（ByteBuf 泄漏防护）
│   ├── WebServerHttpResponse    统一响应接口（extends ServerHttpResponse）
│   │   ├── isHandled(), setHandled()
│   │   ├── sendError(), writeStream(), writeFile()
│   │   └── setTimeout(), getWebContext()
│   ├── NettyServerHttpRequest   Netty 实现
│   ├── NettyServerHttpResponse  Netty 实现
│   ├── RequestContext / RequestAttribute / ConnectionContext
│   ├── BackpressureHandler      背压处理器
│   ├── WriteRespEventListener   写入事件监听
│   └── support/                 HTTP 支持类
│       ├── BodyHttpInputMessage, HttpInputMessagePart
│       ├── NettyMultipartFile, NettyMultipartWebRequest
│       ├── SupportMultipartAggregator, SupportMultipartResolver
│       └── NettyAttributeMessage
│
├── filter/                      WebFilter 过滤器链
│   ├── WebFilter                SPI 接口（extends WebComponent），doFilter(request, response, chain)
│   ├── FilterChain              过滤器链接口
│   ├── DefaultFilterChain       默认实现，终端通过 FilterChainTerminal 回调
│   └── WebFilterRegistry        管理 WebFilter 列表（extends WebComponentContainer）
│       ├── 支持 Ordered 排序
│       ├── 自动注册 Spring 中 WebFilter 类型的 Bean
│       └── 被 DispatcherHandler 在 processRequest 中调用（路由匹配后、同线程执行）
│
├── core/                        核心请求处理
│   │
│   ├── DispatcherHandler        中央分发器，根 HttpHandler
│   │   请求管线（同线程执行 Filter → doHandle）:
│   │     1. WebFilterRegistry         — Filter 链（路由匹配后、参数解析前，与 Handler 同线程）
│   │     2. CorsRegistry              — CORS 检查
│   │     3. InterceptorRegistry       — preHandle
│   │     4. ArgumentResolverRegistry  — 参数解析
│   │     5. InvokableHandlerMethod    — 处理器调用
│   │     6. ReturnValueResolverRegistry — 返回值处理
│   │     7. InterceptorRegistry       — postHandle → afterCompletion
│   │     8. ExceptionRegistry         — 所有异常的兜底处理
│   │
│   ├── mapping/                 请求映射
│   │   ├── MappingRegistry      扫描 @Controller/@RestController，构建路径映射
│   │   │   ├── 解析 @RequestMapping 全部属性
│   │   │   ├── RouterOptimizer 链（前缀/后缀/全路径/循环优化器）
│   │   │   ├── registerMappingAfterInit() 支持动态注册（如 Actuator）
│   │   │   └── supportsPathPattern（AntPathMatcher / PathPattern 兼容）
│   │   ├── PathMappingContext   映射上下文（extends MappingHandlerMethod）
│   │   ├── MappingHandlerMethod 可调用的处理器方法（extends InvokableHandlerMethod）
│   │   │   └── MappingCacheKey  泛型缓存键，用于缓存每方法/每类元数据
│   │   ├── MappingResult        匹配结果（matched / pathMatched / notFound）
│   │   ├── match/               匹配器体系
│   │   │   ├── Matcher          接口: match(), isSameTypeMatcher(), haveAmbiguous()
│   │   │   ├── HttpMethodMatcher
│   │   │   ├── ParamOrHeaderMatcher
│   │   │   ├── ConsumeOrProduceMatcher
│   │   │   ├── MediaTypeExpressionSupport
│   │   │   └── NameValueExpressionSupport
│   │   └── route/               路由引擎
│   │       ├── Router           接口: route(request), getPathRule(), add()
│   │       ├── PathPatternRouter, PathPatternsRouter, SimpleRouter
│   │       └── RouterOptimizer  接口 + FullPath/Suffix/Prefix/Loop 优化器
│   │
│   ├── arg/                     参数解析
│   │   ├── ArgumentResolverRegistry  管理所有参数解析器
│   │   │   ├── StaticArgumentResolverProvider 列表 → 启动时预创建解析器
│   │   │   ├── RuntimeArgumentResolver 列表 → 每次请求动态判断
│   │   │   └── 支持校验（Validation）
│   │   ├── StaticArgumentResolver     简单解析接口
│   │   ├── RuntimeArgumentResolver    运行时解析接口（extends WebComponent）
│   │   ├── MethodArgContext            每方法参数元数据缓存
│   │   ├── StaticArgumentResolverProvider  SPI → 为带注解参数提供解析器
│   │   ├── provider/                   内置注解解析器提供者
│   │   │   ├── PathVariableResolverProvider    @PathVariable
│   │   │   ├── RequestParamResolverProvider    @RequestParam
│   │   │   ├── RequestHeaderResolverProvider   @RequestHeader
│   │   │   ├── RequestBodyResolverProvider     @RequestBody
│   │   │   ├── RequestPartResolverProvider     @RequestPart
│   │   │   ├── ModelAttributeResolverProvider  @ModelAttribute
│   │   │   ├── MultipartFileResolverProvider   MultipartFile
│   │   │   ├── HttpEntityResolverProvider      HttpEntity/RequestEntity
│   │   │   ├── ErrorsResolverProvider          BindingResult/Errors
│   │   │   ├── RequestResolverProvider         请求相关类型
│   │   │   ├── ResponseResolverProvider        响应相关类型
│   │   │   └── LocaleResolverProvider          Locale/TimeZone
│   │   ├── resolver/                 具体解析器实现
│   │   │   ├── RequestBodyResolver    通过 HttpBodyCodecRegistry 读 JSON
│   │   │   ├── ModelAttributeResolver 创建目标对象 + WebDataBinder 绑定
│   │   │   ├── HttpEntityArgResolver
│   │   │   ├── RequestPartResolver
│   │   │   ├── MultiValueMapResolver
│   │   │   └── Abstract* 基类（AbstractNamedValueResolver 等）
│   │   └── databinder/               数据绑定
│   │       ├── WebDataBinderRegistry  管理 DataBinderFactory/ConversionService/Validator
│   │       │   └── 扫描 @ControllerAdvice 的 @InitBinder
│   │       ├── PerfDataBinder         从 MultiValueMap 绑定参数
│   │       └── PerfDataBinderFactory  创建 PerfDataBinder
│   │
│   ├── retval/                   返回值解析
│   │   ├── ReturnValueResolverRegistry  管理 ReturnValueResolver 链
│   │   │   └── 预装 15+ 内置解析器
│   │   ├── ReturnValueResolver          SPI: supportsReturnType() / supportsReturnValue() / resolveReturnValue()
│   │   ├── MethodReturnValueContext     每方法返回值上下文缓存
│   │   └── resolver/                   内置解析器
│   │       ├── JsonBodyReturnValueResolver          @ResponseBody JSON 输出
│   │       ├── HttpEntityReturnValueResolver        ResponseEntity/HttpEntity
│   │       ├── ByteArrayReturnValueResolver         byte[]
│   │       ├── ResourceReturnValueResolver          Resource
│   │       ├── InputStreamReturnValueResolver       InputStream
│   │       ├── FileReturnValueResolver              File
│   │       ├── WrapMessageConverterReturnValueResolver  兜底转换
│   │       └── async/                               异步解析器
│   │           ├── DeferredResultReturnValueResolver
│   │           ├── CallableReturnValueResolver
│   │           ├── ListenableFutureReturnValueResolver
│   │           ├── CompletionStageReturnValueResolver
│   │           └── AsyncTaskReturnValueResolver
│   │
│   ├── interceptor/              处理器拦截器
│   │   ├── HandlerInterceptor     SPI: preHandle/postHandle/afterCompletion
│   │   ├── InterceptorRegistry    管理 InterceptorRegistration（extends WebComponentContainer）
│   │   │   ├── 支持路径包含/排除模式
│   │   │   ├── 缓存按 PathMappingContext 匹配的拦截器
│   │   │   └── 自动注册 Spring 中 HandlerInterceptor / InterceptorRegistration Bean
│   │   ├── InterceptorRegistration  拦截器注册（路径匹配 + 排除 + 排序）
│   │   └── RuntimeMappingInterceptor  运行时路径匹配的拦截器包装
│   │
│   ├── codec/                    HTTP 消息编解码
│   │   ├── HttpBodyConverter     消息转换器（extends GenericHttpMessageConverter + WebComponent）
│   │   ├── HttpBodyCodecRegistry 管理转换器列表（extends WebComponentContainer）
│   │   │   ├── readBody() / writeBody() 内容协商 + 编解码
│   │   │   └── 自动注册 Spring HttpMessageConverter Bean
│   │   ├── WrappedHttpBodyConverter    包装 GenericHttpMessageConverter
│   │   ├── AdaptedHttpBodyConverter    包装非泛型 HttpMessageConverter
│   │   └── interceptor/              编解码拦截器
│   │       ├── HttpBodyCodecInterceptor   SPI: beforeBodyRead/afterBodyRead/beforeBodyWrite
│   │       ├── HttpBodyCodecInterceptorRegistry  按 @ControllerAdvice 管理拦截器
│   │       └── WebComponentControllerAdviceBean   ControllerAdvice + 拦截器的包装
│   │
│   ├── cors/                     跨域处理
│   │   ├── CorsRegistry          管理 CorsRegistration + @CrossOrigin（extends WebComponentContainer）
│   │   ├── CorsRegistration      Builder 模式配置 CORS（allowedOrigins/methods/headers 等）
│   │   ├── WebCorsProcessor      SPI 接口
│   │   ├── PerfCorsProcessor     默认实现（基于 Spring DefaultCorsProcessor）
│   │   ├── CorsUtils             工具方法
│   │   └── provider/             CORS 配置提供者
│   │       ├── CorsConfigurationProvider   SPI
│   │       ├── SimpleCorsConfigurationProvider
│   │       ├── RuntimeMappingCorsConfigurationProvider
│   │       └── NoneCorsConfigurationProvider
│   │
│   ├── exception/                异常处理
│   │   ├── ExceptionRegistry     管理 HandlerExceptionResolver 链（extends WebComponentContainer）
│   │   │   ├── 内置: ExceptionHandlerExceptionResolver + ResponseStatusExceptionResolver
│   │   │   └── 自动注册 Spring 中 HandlerExceptionResolver Bean
│   │   ├── HandlerExceptionResolver  SPI: resolveException(request, response, handler, ex)
│   │   ├── ExceptionHandlerExceptionResolver  扫描 @ControllerAdvice 的 @ExceptionHandler
│   │   ├── ResponseStatusExceptionResolver    处理 @ResponseStatus + ResponseStatusException
│   │   └── ExceptionHandlerAdvice             ControllerAdvice + ExceptionHandlerMethodResolver
│   │
│   ├── async/                    异步支持
│   │   ├── AsyncSupportRegistry  管理 CallableProcessingInterceptor + DeferredResultProcessingInterceptor
│   │   │   ├── startCallableProcessing()
│   │   │   ├── startDeferredResultProcessing()
│   │   │   └── 解析 JsonConverter + AsyncTaskExecutor
│   │   ├── PerfAsyncWebRequest / PerfNativeWebRequest  异步请求抽象
│   │   ├── AsyncSupportUtils
│   │   ├── reactive/             响应式支持（可选，依赖 reactive-streams）
│   │   │   ├── ReactiveConfig
│   │   │   ├── PublisherToDeferredResultAdapter
│   │   │   ├── PublisherToStreamEmitterAdapter
│   │   │   └── ReactiveReturnValueResolver
│   │   └── stream/               流式输出
│   │       ├── StreamEmitter / SseEmitter / SseJsonEmitter
│   │       ├── StreamJsonEmitter / TextStreamEmitter
│   │       ├── StreamSender / StreamSenderFactory
│   │       ├── NettyStreamSender
│   │       └── StreamEmitterReturnValueResolver
│   │
│   ├── resource/                 静态资源
│   │   ├── ResourceHandlerRegistry    管理静态资源映射
│   │   ├── ResourceHandlerRegistration  路径 → 资源目录映射
│   │   └── ResourceRequestHandler      资源请求处理器
│   │
│   ├── pool/                     业务线程池
│   │   └── BizPoolRegistry       管理命名 ThreadPoolExecutor（extends BaseWebComponent）
│   │       ├── 默认池: core=50, max=200, keepAlive=60s
│   │       ├── register(name, executor) 注册自定义线程池
│   │       └── 配合 @RunInPool 注解将处理器调度到指定池
│   │
│   └── invoker/                  方法调用
│       ├── Invoker               调用接口
│       ├── InvokableHandlerMethod  核心可调用方法（extends Spring InvocableHandlerMethod）
│       ├── CustomInvoker         非控制器处理器（如 Actuator 操作）
│       └── FastInvokerGenerator  字节码生成优化反射调用
│
├── json/                         JSON 抽象层
│   ├── JsonConverter             SPI: toJson() / fromJson() 三重重载
│   ├── JacksonConverter          默认实现（Jackson 2.17.2）
│   └── FastjsonConverter         Fastjson 2.0.60 实现（provided）
│
├── annotation/                   自定义注解
│   ├── @Optimize                 标记开启调用优化
│   ├── @ReactiveSupport          配置背压参数（highWaterMark/lowWaterMark/streamEmitterType/timeout）
│   └── @RunInPool                指定业务线程池名称
│
└── util/                         工具类
    ├── PathPatternUtils, WebUtils, MetaUtils
    ├── DefaultLoggerUtil, Pair
    └── support/ContainmentResult, SegKind, Segment


org.springframework.web.context.request/  （src/main/java 内重写，无 Servlet 依赖）
    └── WebAsyncManager, WebAsyncTask, DeferredResult, CallableProcessingInterceptor 等
```

---

## spring-web-support（Servlet 桥接层）

```
spring-web-support
├── SupportDispatcherHandler               扩展 DispatcherHandler，增加 RequestContextHolder 初始化
│
├── arg/provider/
│   ├── HttpServletRequestProvider         解析 HttpServletRequest
│   ├── HttpServletResponseProvider        解析 HttpServletResponse
│   └── WebRequestArgumentResolverProvider 解析 WebRequest / NativeWebRequest
│
├── async/stream/
│   └── ResponseBodyEmitterReturnValueResolver  支持 Spring MVC ResponseBodyEmitter
│
├── codec/interceptor/
│   ├── SupportHttpBodyCodecInterceptorRegistry  扫描 @ControllerAdvice 中的 RequestBodyAdvice/ResponseBodyAdvice
│   ├── RequestBodyAdviceCodecInterceptor        适配 RequestBodyAdvice → HttpBodyCodecInterceptor
│   └── ResponseBodyAdviceCodecInterceptor       适配 ResponseBodyAdvice → HttpBodyCodecInterceptor
│
├── mvc/
│   ├── config/
│   │   └── WebMvcConfigurerBridge          桥接 Spring WebMvcConfigurer → 框架各 Registry
│   │
│   ├── interceptor/
│   │   ├── SupportInterceptorRegistry         扩展 InterceptorRegistry，扫描 Spring MVC HandlerInterceptor
│   │   └── HandlerInterceptorWrapper          适配 Spring MVC HandlerInterceptor → 框架 HandlerInterceptor
│   │
│   ├── arg/
│   │   └── SpringHandlerMethodArgumentResolverAdapter  适配 Spring HandlerMethodArgumentResolver
│   │
│   ├── retval/
│   │   └── SpringHandlerMethodReturnValueHandlerAdapter  适配 Spring HandlerMethodReturnValueHandler
│   │
│   └── exception/
│       └── SpringHandlerExceptionResolverAdapter  适配 Spring HandlerExceptionResolver
│
└── servlet/                              Servlet 桥接
    ├── AbstractFastFailHttpServletRequest/ServletResponse  快速失败的 Servlet 包装
    ├── PerfHttpServletRequest/ServletResponse              基于框架请求/响应的 Servlet 包装
    ├── context/ServletAdapterContext                       持有 Servlet 请求/响应/FilterChain
    └── filter/
        ├── FilterWrapper                   包装 javax.servlet.Filter → WebFilter
        ├── SupportWebFilterRegistry        扩展 WebFilterRegistry，自动注册 Filter Bean
        ├── PerfHttpServletFilterChain      适配 FilterChain → javax.servlet.FilterChain
        └── match/                          路径匹配工具 (Exact/Prefix/Suffix/PathMatch)


org.springframework.web.servlet/         （src/main/java 内重写）
├── HandlerInterceptor, AsyncHandlerInterceptor
├── ModelAndView, View
├── LocaleResolver, LocaleContextResolver
├── NoHandlerFoundException
└── HandlerExceptionResolver

org.springframework.web.servlet.handler/
├── MappedInterceptor
└── WebRequestHandlerInterceptorAdapter

org.springframework.web.servlet.config.annotation/
├── InterceptorRegistration
├── WebMvcConfigurer
├── CorsRegistry, CorsRegistration
├── ResourceHandlerRegistry, ResourceHandlerRegistration
├── PathMatchConfigurer
├── AsyncSupportConfigurer
├── ContentNegotiationConfigurer
├── DefaultServletHandlerConfigurer
├── ViewControllerRegistry
├── ViewResolverRegistry
└── ValidatorRegistration

org.springframework.web.servlet.mvc.method.annotation/
├── RequestBodyAdvice, ResponseBodyAdvice
├── ResponseBodyEmitter, SseEmitter, StreamingResponseBody
├── ResponseEntityExceptionHandler
└── AdapterUtil
```

---

## spring-boot-starter-web（自动配置）

```
spring-boot-starter-web
├── autoconfigure/
│   ├── SpringWebAutoConfiguration              核心自动装配
│   │   ├── ApplicationProperties
│   │   ├── WebContext → 驱动整个组件生命周期
│   │   ├── 所有 Registry 组件
│   │   ├── NettyHttpServer + 可选 SSL
│   │   ├── Validator（条件：javax.validation.Validator 在 classpath）
│   │   └── 启动时检测 SpringMVC 冲突并抛出 IllegalStateException
│   │
│   ├── SpringWebSupportAutoConfiguration       Support 模块自动装配（条件：spring-web-support 在 classpath）
│   │   ├── SupportDispatcherHandler
│   │   ├── SupportInterceptorRegistry
│   │   ├── SupportHttpBodyCodecInterceptorRegistry
│   │   ├── HttpServletRequest/Response Provider
│   │   ├── WebRequestArgumentResolverProvider
│   │   ├── SupportWebFilterRegistry + FilterWrapper
│   │   ├── ResponseBodyEmitterReturnValueResolver
│   │   ├── WebMvcConfigurerBridge（桥接 WebMvcConfigurer 实现）
│   │   ├── SpringHandlerMethodArgumentResolverAdapter
│   │   ├── SpringHandlerMethodReturnValueHandlerAdapter
│   │   └── SpringHandlerExceptionResolverAdapter
│   │
│   ├── SpringDataWebCompatibilityAutoConfiguration  Spring Data 兼容（条件：ProjectingArgumentResolverRegistrar 在 classpath）
│   │   └── 启动时移除 ProjectingArgumentResolverRegistrar 的 BPP
│   │      防止因缺少 RequestMappingHandlerAdapter 引发 NoClassDefFoundError
│   │      投影解析仍通过 WebMvcConfigurerBridge 桥接
│   │
│   ├── ActuatorEndpointAutoConfiguration      Actuator 端点自动装配（条件：ExposableWebEndpoint 在 classpath）
│   │   ├── WebEndpointDiscoverer
│   │   ├── ActuatorEndpointHandlerMapping      将 Actuator 端点注册为框架路由
│   │   ├── ManagementServerInfrastructure      管理端口组件容器
│   │   ├── ManagementDispatcherHandler         管理端口分发器
│   │   ├── ManagementNettyHttpServer           独立 Netty 服务器
│   │   ├── OperationHandlerInvoker             端点操作调用器
│   │   └── LinksOperationInvoker               根路径 links 处理
│   │
│   └── support/
│       └── WebServerApplicationContextFactory  强制 AnnotationConfigApplicationContext
│
└── resources/META-INF/
    ├── spring.factories                  注册 4 个 AutoConfiguration + ApplicationContextFactory
    └── additional-spring-configuration-metadata.json  配置元数据（IDE 提示）
```

---

## 组件生命周期

所有核心组件继承 `BaseWebComponent`，通过 `WebContext`（implements `InitializingBean`）驱动：

```
Spring 容器启动
  ↓
WebContext.afterPropertiesSet()
  ↓
initWithWebContext()         → 依赖装配，容器间引用注入
  ↓
initComponentPhase1()        → 扫描 Spring Bean、注册 Mapping、构建策略列表
  ↓
initComponentPhase2()        → 构建内部数据结构、路由优化
  ↓
initComponentPhase3()        → 预热、fail-fast 校验
  ↓
就绪，开始处理请求
  ↓
Spring 容器关闭
  ↓
destroyComponent()           → 资源释放
```

### Registry 类体系

```
WebComponent (interface)
  └── LifecycleWebComponent (interface, 三阶段生命周期)
       └── BaseWebComponent (abstract, 持有 WebContext)
            └── WebComponentContainer (abstract, Map<String,WebComponent>)
                 ├── WebContext             顶层容器
                 ├── WebFilterRegistry      过滤器
                 ├── MappingRegistry        路由映射
                 ├── InterceptorRegistry    拦截器
                 ├── ArgumentResolverRegistry  参数解析
                 ├── ReturnValueResolverRegistry 返回值解析
                 ├── HttpBodyCodecRegistry  消息编解码
                 ├── HttpBodyCodecInterceptorRegistry  编解码拦截器
                 ├── CorsRegistry           CORS
                 ├── ExceptionRegistry      异常
                 ├── AsyncSupportRegistry   异步
                 ├── ResourceHandlerRegistry  静态资源
                 └── WebDataBinderRegistry  数据绑定
```

---

## 扩展点（SPI）总览

| SPI 接口 | 注册方式 | 切入点 | 对应 Registry |
|----------|----------|--------|---------------|
| `WebFilter` | Spring Bean | DispatcherHandler 内路由匹配后、doHandle 前（同线程） | `WebFilterRegistry` |
| `HandlerInterceptor` | Spring Bean / `InterceptorRegistration` | 控制器方法前后 | `InterceptorRegistry` |
| `HttpBodyCodecInterceptor` | Spring Bean | 请求体读/响应体写 | `HttpBodyCodecInterceptorRegistry` |
| `HandlerExceptionResolver` | Spring Bean | 任何步骤的异常 | `ExceptionRegistry` |
| `ReturnValueResolver` | Spring Bean | 控制器返回值 | `ReturnValueResolverRegistry` |
| `RuntimeArgumentResolver` | Spring Bean | 每次请求参数解析 | `ArgumentResolverRegistry` |
| `StaticArgumentResolverProvider` | Spring Bean | 为注解参数提供解析器 | `ArgumentResolverRegistry` |
| `HttpBodyConverter` | Spring Bean | HTTP 消息格式转换 | `HttpBodyCodecRegistry` |
| `JsonConverter` | Spring Bean | JSON 序列化/反序列化 | 通过 `AsyncSupportRegistry` 引用 |
| `RouterOptimizer` | Spring Bean | 路由结构优化 | `MappingRegistry` |
| `WebCorsProcessor` | Spring Bean | CORS 处理策略 | `CorsRegistry` |
| `CorsConfigurationProvider` | 内部 | 每次请求 CORS 配置 | `CorsRegistry` |
| `CustomInvoker` | 内部 | 非控制器方法调用 | `MappingRegistry` |
| `WebComponent` / `BaseWebComponent` | Spring Bean | 自定义生命周期组件 | `WebComponentContainer` |

---

## 配置项

| 键 | 默认值 | 说明 |
|----|--------|------|
| `server.port` | `8080` | Netty 监听端口 |
| `server.servlet.context-path` | `/` | 上下文路径 |
| `server.http.max-content-length` | `1048576` | 最大请求体（字节） |
| `server.http.timeout` | 无 | 请求超时（毫秒） |
| `server.check-on-startup` | `true` | 启动时校验 Mapping |
| `pool.core-pool-size` | `50` | 默认业务线程池核心数 |
| `pool.max-pool-size` | `200` | 默认业务线程池最大数 |
| `pool.keep-alive-time` | `60` | 线程存活时间（秒） |
| `management.server.port` | 无 | Actuator 独立管理端口 |

---

## 请求处理管线（完整）

```
Netty I/O 线程
  │ NettyServerHttpRequest/Response 创建
  ▼
BackpressureHandler
  ▼
DispatcherHandler (根 HttpHandler)
  ├── MappingRegistry.match(request)
  │   └── RouterOptimizer 链 → Matcher 链 → PathMappingContext
  │
  ├── [404/405/CORS 预检] 直接返回，不走后续步骤
  │
  └── handleWithPathMappingContext()
      └── @RunInPool ?
          ├── YES: executor.execute(() -> processRequest())  ← 业务线程池
          └── NO:  processRequest()                          ← EventLoop
                      │
                      ├── WebFilterRegistry (有序 WebFilter 链)   ← 同线程！
                      ├── CorsRegistry.getProvider(mapping) → preProcess()
                      ├── InterceptorRegistry.getInterceptors(mapping) → preHandle()
                      ├── ArgumentResolverRegistry.resolveArguments(method, request, response)
                      │   ├── 静态解析器（预创建）: @PathVariable, @RequestParam, @RequestBody 等
                      │   └── 运行时解析器: RuntimeArgumentResolver
                      ├── InvokableHandlerMethod.invoke(args)
                      │   └── 可选: FastInvokerGenerator 字节码调用
                      ├── ReturnValueResolverRegistry.resolve(returnValue)
                      │   ├── JSON: JsonBodyReturnValueResolver → HttpBodyCodecRegistry.writeBody()
                      │   ├── 异步: DeferredResult/Callable → AsyncSupportRegistry
                      │   └── 流式: StreamEmitter → NettyStreamSender
                      ├── InterceptorRegistry.postHandle()
                      ├── InterceptorRegistry.afterCompletion()
                      └── ExceptionRegistry.resolveException()
                          └── @ExceptionHandler / ResponseStatusException / 自定义 HandlerExceptionResolver
```

---

## 设计原则

1. **避免隐藏开销** — 请求路径上的所有操作都应是显式的
2. **避免隐式对象创建** — 请求路径不 new ArrayList、不装箱
3. **避免线程切换** — 不需要 `@RunInPool` 的情况下直接在 EventLoop 处理
4. **避免阻塞** — EventLoop 线程不执行阻塞操作
5. **避免反射** — 启动时一次性完成元数据解析 + 缓存，运行时零反射
6. **避免魔法行为** — 每个扩展点都是显式 SPI

## 请求路径性能保障

- `MappingCacheKey` 缓存每方法/每类的元数据
- `@RunInPool` 的线程池名称在启动时解析并缓存
- `StaticArgumentResolverProvider` 在启动时预创建解析器实例
- `RouterOptimizer` 在启动时构建高效路由结构
- `FastInvokerGenerator` 可选字节码生成替代反射调用
- 所有 Registry 组件在 `initComponentPhase3` 完成 fail-fast 校验
