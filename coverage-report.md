# 单元测试覆盖率报告 / Unit Test Coverage Report

- **生成时间 / Generated at**：2026-09-03 21:53:01
- **环境 / Environment**：Windows_NT / java version "17.0.9" 2023-10-17 LTS
- **覆盖范围 / Scope**：库模块单元测试（JaCoCo，基于 target/site/jacoco/jacoco.csv）
  Library module unit tests (JaCoCo, based on target/site/jacoco/jacoco.csv)
- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）
  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)
- **汇总 / Summary**：行/Line 87.7% · 分支/Branch 77% · 指令/Instr 87.4% · 方法/Method 89.5%

## 一、覆盖率总览 / 1. Coverage Overview

| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |
|---|---|---|---|---|---|---|---|---|---|
| spring-web | 91.2% | 6178/6774 | 81.7% | 2761/3378 | 90.4% | 93.2% | 205 | ≥90% | ✅ |
| spring-web-view | 96.7% | 323/334 | 78.8% | 134/170 | 97% | 99% | 15 | ≥80% | ✅ |
| spring-web-support | 82.4% | 2140/2596 | 68.4% | 636/930 | 82.5% | 83.8% | 86 | ≥80% | ✅ |
| spring-web-batch | 82.3% | 312/379 | 70.8% | 97/137 | 83.9% | 91.3% | 18 | ≥80% | ✅ |
| spring-web-websocket | 82% | 900/1097 | 68% | 278/409 | 82.6% | 91.8% | 24 | ≥80% | ✅ |
| spring-boot-starter-web | 83.6% | 923/1104 | 69.5% | 324/466 | 83% | 79.8% | 47 | ≥80% | ✅ |
| **汇总 / Total** | **87.7%** | **10776/12284** | **77%** | **4230/5490** | **87.4%** | **89.5%** | | | |

## 二、测试规模 / 2. Test Count

| 模块 / Module | 用例数 / Tests |
|---|---|
| spring-web | 2011 |
| spring-web-view | 57 |
| spring-web-support | 732 |
| spring-web-batch | 76 |
| spring-web-websocket | 137 |
| spring-boot-starter-web | 233 |
| **库模块合计 / Library total** | **3246** |
| E2E spring-web-test | 253 |
| E2E spring-web-support-test | 165 |
| **E2E 合计 / E2E total** | **418** |

> 说明 / Note：E2E 用例默认不随本脚本执行，上表基于已存在的 surefire 报告；如需刷新请单独运行
> E2E tests are not run by this script; the table above reflects existing surefire reports.
> 刷新命令 / To refresh: mvn test -pl spring-web-test,spring-web-support-test

## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)

### spring-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Http2ChannelInitializer.new ChannelInboundHandlerAdapter() {...} | 41 | 42 | 2.4% |
| Http2ChannelInitializer.Http2OrHttp1Handler | 34 | 34 | 0% |
| BizPoolRegistry | 25 | 140 | 82.1% |
| CorsRegistry | 24 | 112 | 78.6% |
| DispatcherHandler | 23 | 159 | 85.5% |

### spring-web-view

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| ViewReturnValueResolver | 5 | 63 | 92.1% |
| BeetlViewResolver | 2 | 21 | 90.5% |
| RedirectView | 2 | 35 | 94.3% |
| ViewResolverRegistry | 1 | 41 | 97.6% |
| FreemarkerViewResolver | 1 | 20 | 95% |

### spring-web-support

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| PerfHttpServletResponse | 48 | 145 | 66.9% |
| SupportDispatcherHandler | 31 | 38 | 18.4% |
| AbstractFastFailHttpServletRequest | 29 | 74 | 60.8% |
| PerfHttpServletRequest | 29 | 286 | 89.9% |
| SessionAttributesInterceptor | 28 | 49 | 42.9% |

### spring-web-batch

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| DisruptorQueue | 28 | 83 | 66.3% |
| BatchRegistry | 19 | 48 | 60.4% |
| BatchScanner | 12 | 97 | 87.6% |
| NoOpBatchMetrics | 3 | 8 | 62.5% |
| BufferingBatchHandler | 3 | 49 | 93.9% |

### spring-web-websocket

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| JsrEndpointWebSocketHandler | 55 | 126 | 56.3% |
| WebSocketRoutingHandler | 53 | 250 | 78.8% |
| NettyWebSocketSession | 29 | 116 | 75% |
| JsrCodecRegistry | 15 | 110 | 86.4% |
| JsrRemoteEndpointAsync | 15 | 57 | 73.7% |

### spring-boot-starter-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| SpringWebSupportAutoConfiguration | 27 | 61 | 55.7% |
| ActuatorPathMappingContext | 26 | 65 | 60% |
| ActuatorEndpointAutoConfiguration | 16 | 25 | 36% |
| Boot4WebServerInitializedEventBridge.new ClassWriter() {...} | 14 | 15 | 6.7% |
| ManagementNettyHttpServer.new ChannelInitializer() {...} | 10 | 11 | 9.1% |


