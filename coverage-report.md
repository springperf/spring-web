# 单元测试覆盖率报告 / Unit Test Coverage Report

- **生成时间 / Generated at**：2026-09-09 23:23:56
- **环境 / Environment**：Windows_NT / java version "17.0.9" 2023-10-17 LTS
- **覆盖范围 / Scope**：库模块单元测试（JaCoCo，基于 target/site/jacoco/jacoco.csv）
  Library module unit tests (JaCoCo, based on target/site/jacoco/jacoco.csv)
- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）
  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)
- **汇总 / Summary**：行/Line 92.1% · 分支/Branch 81.5% · 指令/Instr 91.5% · 方法/Method 92.5%

## 一、覆盖率总览 / 1. Coverage Overview

| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |
|---|---|---|---|---|---|---|---|---|---|
| spring-web | 92.7% | 6164/6647 | 82.6% | 2769/3354 | 91.2% | 93.4% | 203 | ≥90% | ✅ |
| spring-web-view | 95.5% | 236/247 | 81.3% | 109/134 | 96.9% | 98% | 11 | ≥80% | ✅ |
| spring-web-servlet | 91.5% | 1633/1784 | 79.5% | 505/635 | 91.8% | 92.6% | 45 | ≥80% | ✅ |
| spring-web-mvc-support | 92.4% | 769/832 | 84.2% | 256/304 | 93.3% | 88.2% | 40 | ≥80% | ✅ |
| spring-web-batch | 91.3% | 345/378 | 79.6% | 109/137 | 92.1% | 94.6% | 18 | ≥80% | ✅ |
| spring-web-websocket | 90.5% | 1004/1109 | 80.7% | 335/415 | 90.9% | 95.4% | 24 | ≥80% | ✅ |
| spring-boot-starter-web | 89.4% | 897/1003 | 75.5% | 326/432 | 90.5% | 85.5% | 43 | ≥80% | ✅ |
| **汇总 / Total** | **92.1%** | **11048/12000** | **81.5%** | **4409/5411** | **91.5%** | **92.5%** | | | |

## 二、测试规模 / 2. Test Count

| 模块 / Module | 用例数 / Tests |
|---|---|
| spring-web | 2050 |
| spring-web-view | 52 |
| spring-web-servlet | 543 |
| spring-web-mvc-support | 281 |
| spring-web-batch | 83 |
| spring-web-websocket | 170 |
| spring-boot-starter-web | 241 |
| **库模块合计 / Library total** | **3420** |
| E2E spring-web-test | 255 |
| E2E spring-web-support-test | 168 |
| **E2E 合计 / E2E total** | **423** |

> 说明 / Note：E2E 用例默认不随本脚本执行，上表基于已存在的 surefire 报告；如需刷新请单独运行
> E2E tests are not run by this script; the table above reflects existing surefire reports.
> 刷新命令 / To refresh: mvn test -pl spring-web-test,spring-web-support-test

## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)

### spring-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Http2ChannelInitializer.new ChannelInboundHandlerAdapter() {...} | 41 | 42 | 2.4% |
| Http2ChannelInitializer.Http2OrHttp1Handler | 34 | 34 | 0% |
| Http2ChannelInitializer | 16 | 72 | 77.8% |
| DispatcherHandler | 16 | 157 | 89.8% |
| ModelArgumentResolverProvider.ModelStaticArgumentResolver | 15 | 65 | 76.9% |

### spring-web-view

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| ViewReturnValueResolver | 5 | 64 | 92.2% |
| RedirectView | 2 | 35 | 94.3% |
| BeetlViewResolver | 2 | 21 | 90.5% |
| ViewResolverRegistry | 1 | 42 | 97.6% |
| FreemarkerViewResolver | 1 | 20 | 95% |

### spring-web-servlet

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| PerfServletContext | 35 | 244 | 85.7% |
| PerfHttpServletRequest | 19 | 287 | 93.4% |
| SupportDispatcherHandler.SessionFlushListener | 14 | 17 | 17.6% |
| PerfHttpSession | 8 | 96 | 91.7% |
| SupportServletRegistry | 8 | 63 | 87.3% |

### spring-web-mvc-support

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| SseEmitter | 11 | 23 | 52.2% |
| WebMvcConfigurer | 11 | 19 | 42.1% |
| WebMvcConfigurerBridge | 6 | 230 | 97.4% |
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
| WebSocketRoutingHandler | 46 | 252 | 81.7% |
| NettyWebSocketSession | 19 | 116 | 83.6% |
| JsrEndpointWebSocketHandler | 11 | 133 | 91.7% |
| JsrCodecRegistry | 7 | 110 | 93.6% |
| JsrEndpointMetadata | 6 | 103 | 94.2% |

### spring-boot-starter-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| ManagementNettyHttpServer.new ChannelInitializer() {...} | 13 | 14 | 7.1% |
| ManagementNettyHttpServer | 10 | 58 | 82.8% |
| PerfApplicationFactory | 9 | 64 | 85.9% |
| SpringWebAutoConfiguration | 7 | 21 | 66.7% |
| SpringWebAutoConfiguration.MicrometerWebMetricsConfiguration | 7 | 15 | 53.3% |


