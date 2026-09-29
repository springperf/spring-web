# 单元测试覆盖率报告 / Unit Test Coverage Report

- **生成时间 / Generated at**：2026-09-29 09:21:28
- **环境 / Environment**：MINGW64_NT-10.0-19045 / 
- **覆盖范围 / Scope**：库模块，**单测 + E2E 合并口径**（JaCoCo，coverage-aggregate 模块 report-aggregate 合并后的 jacoco.csv）
  Library modules, unit tests **plus E2E** (JaCoCo, merged jacoco.csv from the coverage-aggregate module)
- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）
  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)
- **汇总 / Summary**：行/Line 93.5% · 分支/Branch 84.7% · 指令/Instr 93.7% · 方法/Method 95.7%

## 一、覆盖率总览 / 1. Coverage Overview

| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |
|---|---|---|---|---|---|---|---|---|---|
| spring-web | 93.7% | 8350/8912 | 86.1% | 3968/4607 | 93.6% | 95.5% | 229 | ≥90% | ✅ |
| spring-web-view | 93.8% | 394/420 | 77.4% | 175/226 | 92.9% | 99.1% | 18 | ≥80% | ✅ |
| spring-web-servlet | 93.3% | 2269/2432 | 84.1% | 819/974 | 93.8% | 96.7% | 54 | ≥80% | ✅ |
| spring-web-mvc-support | 95.6% | 911/953 | 88.3% | 302/342 | 96.4% | 92.8% | 42 | ≥80% | ✅ |
| spring-web-batch | 91.9% | 362/394 | 81.7% | 125/153 | 93.1% | 95.7% | 18 | ≥80% | ✅ |
| spring-web-websocket | 90.7% | 1067/1176 | 82.2% | 384/467 | 92.0% | 95.5% | 26 | ≥80% | ✅ |
| spring-boot-starter-web | 93.4% | 1254/1342 | 78.5% | 518/660 | 95.0% | 95.8% | 52 | ≥80% | ✅ |
| **汇总 / Total** | **93.5%** | **14607/15629** | **84.7%** | **6291/7429** | **93.7%** | **95.7%** | | | |

## 二、测试规模 / 2. Test Count

| 模块 / Module | 用例数 / Tests |
|---|---|
| spring-web | 2336 |
| spring-web-view | 73 |
| spring-web-servlet | 641 |
| spring-web-mvc-support | 295 |
| spring-web-batch | 91 |
| spring-web-websocket | 183 |
| spring-boot-starter-web | 288 |
| **库模块合计 / Library total** | **3907** |
| E2E spring-web-test | 267 |
| E2E spring-web-support-test | 523 |
| **E2E 合计 / E2E total** | **790** |

> 说明 / Note：本脚本会运行库模块 + E2E 模块；E2E 触发的库模块覆盖由聚合模块合并后计入第一节。
> Both library and E2E modules are run; E2E-driven library coverage is merged in by the aggregate module.

## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)

### spring-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Http2ChannelInitializer.Http2OrHttp1Handler | 43 | 43 | 0.0% |
| SupportMultipartAggregator | 23 | 85 | 72.9% |
| Http2ChannelInitializer.new ChannelInboundHandlerAdapter() {...} | 21 | 42 | 50.0% |
| ApplicationProperties | 21 | 149 | 85.9% |
| NettyServerHttpResponse | 20 | 374 | 94.7% |
### spring-web-view

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| FreemarkerViewResolver | 16 | 41 | 61.0% |
| ThymeleafViewResolver | 3 | 40 | 92.5% |
| ViewReturnValueResolver | 2 | 62 | 96.8% |
| RedirectView | 2 | 35 | 94.3% |
| BeetlViewResolver | 2 | 31 | 93.5% |
### spring-web-servlet

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| FileHttpSessionStorage | 42 | 219 | 80.8% |
| PerfHttpServletRequest | 23 | 365 | 93.7% |
| PerfServletContext | 15 | 261 | 94.3% |
| PerfHttpServletResponse | 10 | 268 | 96.3% |
| SupportServletRegistry | 8 | 63 | 87.3% |
### spring-web-mvc-support

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| WebMvcConfigurer | 9 | 19 | 52.6% |
| ModelAndView | 6 | 67 | 91.0% |
| WebMvcConfigurerBridge | 4 | 273 | 98.5% |
| SupportInterceptorRegistry | 4 | 34 | 88.2% |
| ResponseBodyEmitterReturnValueResolver | 4 | 31 | 87.1% |
### spring-web-batch

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| DisruptorQueue | 20 | 97 | 79.4% |
| BatchScanner | 6 | 96 | 93.8% |
| NoOpBatchMetrics | 3 | 8 | 62.5% |
| BatchRequest | 2 | 15 | 86.7% |
| BufferingBatchHandler | 1 | 47 | 97.9% |
### spring-web-websocket

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| WebSocketRoutingHandler | 44 | 260 | 83.1% |
| NettyWebSocketSession | 20 | 130 | 84.6% |
| JsrEndpointWebSocketHandler | 13 | 138 | 90.6% |
| JsrCodecRegistry | 7 | 110 | 93.6% |
| JsrEndpointMetadata | 6 | 106 | 94.3% |
### spring-boot-starter-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Boot4WebServerInitializedEventBridge.new ClassWriter() {...} | 14 | 15 | 6.7% |
| PerfApplicationFactory | 9 | 62 | 85.5% |
| ManagementNettyHttpServer | 9 | 67 | 86.6% |
| SpringWebAutoConfiguration.MicrometerWebMetricsConfiguration | 7 | 14 | 50.0% |
| OpenApiAdapter | 7 | 238 | 97.1% |

