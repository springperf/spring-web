# 单元测试覆盖率报告 / Unit Test Coverage Report

- **生成时间 / Generated at**：2026-09-20 20:15:41
- **环境 / Environment**：Windows_NT / java version "17.0.9" 2023-10-17 LTS
- **覆盖范围 / Scope**：库模块，**单测 + E2E 合并口径**（JaCoCo，coverage-aggregate 模块 report-aggregate 合并后的 jacoco.csv）
  Library modules, unit tests **plus E2E** (JaCoCo, merged jacoco.csv from the coverage-aggregate module)
- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）
  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)
- **汇总 / Summary**：行/Line 93.8% · 分支/Branch 84.9% · 指令/Instr 93.7% · 方法/Method 95.6%

## 一、覆盖率总览 / 1. Coverage Overview

| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |
|---|---|---|---|---|---|---|---|---|---|
| spring-web | 93.8% | 8208/8749 | 86.3% | 3919/4539 | 93.5% | 95.4% | 228 | ≥90% | ✅ |
| spring-web-view | 93.5% | 388/415 | 77.2% | 176/228 | 92.8% | 99.1% | 18 | ≥80% | ✅ |
| spring-web-servlet | 94.3% | 2171/2303 | 84.6% | 753/890 | 94.4% | 96.6% | 54 | ≥80% | ✅ |
| spring-web-mvc-support | 95.9% | 894/932 | 88.3% | 302/342 | 96.4% | 92.8% | 42 | ≥80% | ✅ |
| spring-web-batch | 92.8% | 362/390 | 81.7% | 125/153 | 93.1% | 95.7% | 18 | ≥80% | ✅ |
| spring-web-websocket | 90.9% | 1049/1154 | 83% | 371/447 | 91.9% | 95.5% | 26 | ≥80% | ✅ |
| spring-boot-starter-web | 94.2% | 1176/1249 | 77.6% | 447/576 | 94.6% | 95.7% | 52 | ≥80% | ✅ |
| **汇总 / Total** | **93.8%** | **14248/15192** | **84.9%** | **6093/7175** | **93.7%** | **95.6%** | | | |

## 二、测试规模 / 2. Test Count

| 模块 / Module | 用例数 / Tests |
|---|---|
| spring-web | 2315 |
| spring-web-view | 72 |
| spring-web-servlet | 620 |
| spring-web-mvc-support | 295 |
| spring-web-batch | 91 |
| spring-web-websocket | 178 |
| spring-boot-starter-web | 272 |
| **库模块合计 / Library total** | **3843** |
| E2E spring-web-test | 267 |
| E2E spring-web-support-test | 523 |
| **E2E 合计 / E2E total** | **790** |

> 说明 / Note：本脚本会运行库模块 + E2E 模块；E2E 触发的库模块覆盖由聚合模块合并后计入第一节。
> Both library and E2E modules are run; E2E-driven library coverage is merged in by the aggregate module.

## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)

### spring-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Http2ChannelInitializer.Http2OrHttp1Handler | 43 | 43 | 0% |
| SupportMultipartAggregator | 23 | 86 | 73.3% |
| Http2ChannelInitializer.new ChannelInboundHandlerAdapter() {...} | 21 | 42 | 50% |
| ApplicationProperties | 21 | 141 | 85.1% |
| NettyServerHttpResponse | 20 | 373 | 94.6% |

### spring-web-view

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| FreemarkerViewResolver | 17 | 42 | 59.5% |
| ThymeleafViewResolver | 3 | 40 | 92.5% |
| ViewReturnValueResolver | 2 | 64 | 96.9% |
| BeetlViewResolver | 2 | 31 | 93.5% |
| RedirectView | 2 | 35 | 94.3% |

### spring-web-servlet

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| FileHttpSessionStorage | 30 | 147 | 79.6% |
| PerfHttpServletRequest | 19 | 348 | 94.5% |
| PerfServletContext | 14 | 260 | 94.6% |
| PerfHttpServletResponse | 10 | 265 | 96.2% |
| SupportServletRegistry | 8 | 63 | 87.3% |

### spring-web-mvc-support

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| WebMvcConfigurer | 9 | 19 | 52.6% |
| ModelAndView | 6 | 67 | 91% |
| SupportInterceptorRegistry | 4 | 33 | 87.9% |
| ResponseBodyEmitterReturnValueResolver | 4 | 29 | 86.2% |
| WebMvcConfigurerBridge | 3 | 262 | 98.9% |

### spring-web-batch

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| DisruptorQueue | 20 | 93 | 78.5% |
| NoOpBatchMetrics | 3 | 8 | 62.5% |
| BatchScanner | 3 | 97 | 96.9% |
| BatchRequest | 2 | 13 | 84.6% |
| BufferingBatchHandler | 0 | 49 | 100% |

### spring-web-websocket

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| WebSocketRoutingHandler | 44 | 256 | 82.8% |
| NettyWebSocketSession | 19 | 127 | 85% |
| JsrEndpointWebSocketHandler | 11 | 133 | 91.7% |
| JsrCodecRegistry | 7 | 110 | 93.6% |
| JsrEndpointMetadata | 6 | 103 | 94.2% |

### spring-boot-starter-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Boot4WebServerInitializedEventBridge.new ClassWriter() {...} | 14 | 15 | 6.7% |
| PerfApplicationFactory | 9 | 64 | 85.9% |
| ManagementNettyHttpServer | 9 | 69 | 87% |
| SpringWebAutoConfiguration.MicrometerWebMetricsConfiguration | 7 | 15 | 53.3% |
| OperationHandlerInvoker | 5 | 43 | 88.4% |

