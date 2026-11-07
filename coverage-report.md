# 单元测试覆盖率报告 / Unit Test Coverage Report

- **生成时间 / Generated at**：2026-10-12 16:02:18
- **环境 / Environment**：Windows_NT / openjdk version "1.8.0_502"
- **覆盖范围 / Scope**：库模块，**单测 + E2E 合并口径**（JaCoCo，coverage-aggregate 模块 report-aggregate 合并后的 jacoco.csv）
  Library modules, unit tests **plus E2E** (JaCoCo, merged jacoco.csv from the coverage-aggregate module)
- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）
  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)
- **汇总 / Summary**：行/Line 90% · 分支/Branch 78.4% · 指令/Instr 89.3% · 方法/Method 92.1%

## 一、覆盖率总览 / 1. Coverage Overview

| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |
|---|---|---|---|---|---|---|---|---|---|
| spring-web | 90.2% | 7844/8695 | 79.2% | 3628/4581 | 88.7% | 92.6% | 228 | ≥90% | ✅ |
| spring-web-view | 91.1% | 308/338 | 73.4% | 141/192 | 89.9% | 97.2% | 15 | ≥80% | ✅ |
| spring-web-servlet | 87% | 2097/2409 | 72.9% | 701/962 | 87% | 91.5% | 51 | ≥80% | ✅ |
| spring-web-mvc-support | 93.3% | 877/940 | 84.5% | 289/342 | 94% | 89.5% | 42 | ≥80% | ✅ |
| spring-web-batch | 91.3% | 345/378 | 79.6% | 109/137 | 92.1% | 94.6% | 18 | ≥80% | ✅ |
| spring-web-websocket | 90.5% | 1041/1150 | 81.3% | 365/449 | 91.1% | 95.5% | 24 | ≥80% | ✅ |
| spring-boot-starter-web | 90.5% | 1033/1142 | 77.3% | 419/542 | 91.6% | 86.8% | 45 | ≥80% | ✅ |
| **汇总 / Total** | **90%** | **13545/15052** | **78.4%** | **5652/7205** | **89.3%** | **92.1%** | | | |

## 二、测试规模 / 2. Test Count

| 模块 / Module | 用例数 / Tests |
|---|---|
| spring-web | 2375 |
| spring-web-view | 74 |
| spring-web-servlet | 633 |
| spring-web-mvc-support | 295 |
| spring-web-batch | 83 |
| spring-web-websocket | 181 |
| spring-boot-starter-web | 271 |
| **库模块合计 / Library total** | **3912** |
| E2E spring-web-test | 263 |
| E2E spring-web-support-test | 526 |
| **E2E 合计 / E2E total** | **789** |

> 说明 / Note：本脚本会运行库模块 + E2E 模块；E2E 触发的库模块覆盖由聚合模块合并后计入第一节。
> Both library and E2E modules are run; E2E-driven library coverage is merged in by the aggregate module.

## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)

### spring-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| ResourceRequestHandler | 61 | 291 | 79% |
| NettyServerHttpResponse | 58 | 374 | 84.5% |
| NettyHttpHandler | 47 | 162 | 71% |
| Http2ChannelInitializer.Http2OrHttp1Handler | 43 | 43 | 0% |
| Http2ChannelInitializer.new ChannelInboundHandlerAdapter() {...} | 41 | 42 | 2.4% |

### spring-web-view

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| FreemarkerViewResolver | 16 | 41 | 61% |
| ViewReturnValueResolver | 5 | 64 | 92.2% |
| ThymeleafViewResolver | 3 | 40 | 92.5% |
| RedirectView | 2 | 35 | 94.3% |
| BeetlViewResolver | 2 | 31 | 93.5% |

### spring-web-servlet

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| PerfHttpServletResponse | 62 | 262 | 76.3% |
| FileHttpSessionStorage | 55 | 266 | 79.3% |
| PerfServletContext | 38 | 267 | 85.8% |
| PerfHttpServletRequest | 36 | 362 | 90.1% |
| SupportDispatcherHandler.SessionFlushListener | 14 | 17 | 17.6% |

### spring-web-mvc-support

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| SseEmitter | 11 | 23 | 52.2% |
| WebMvcConfigurer | 10 | 19 | 47.4% |
| WebMvcConfigurerBridge | 6 | 271 | 97.8% |
| ModelAndView | 6 | 67 | 91% |
| ResponseBodyEmitterReturnValueResolver | 5 | 29 | 82.8% |

### spring-web-batch

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| DisruptorQueue | 22 | 83 | 73.5% |
| NoOpBatchMetrics | 3 | 8 | 62.5% |
| BufferingBatchHandler | 3 | 49 | 93.9% |
| BatchRequest | 2 | 13 | 84.6% |
| BatchScanner | 2 | 96 | 97.9% |

### spring-web-websocket

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| WebSocketRoutingHandler | 46 | 258 | 82.2% |
| NettyWebSocketSession | 19 | 128 | 85.2% |
| JsrEndpointWebSocketHandler | 13 | 138 | 90.6% |
| JsrCodecRegistry | 7 | 110 | 93.6% |
| JsrEndpointMetadata | 6 | 107 | 94.4% |

### spring-boot-starter-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| ManagementNettyHttpServer.new ChannelInitializer() {...} | 15 | 16 | 6.2% |
| ManagementNettyHttpServer | 10 | 68 | 85.3% |
| PerfApplicationFactory | 9 | 64 | 85.9% |
| ActuatorEndpointHandlerMapping | 7 | 55 | 87.3% |
| SpringWebServletAutoConfiguration | 7 | 54 | 87% |

