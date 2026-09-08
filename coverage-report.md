# 单元测试覆盖率报告 / Unit Test Coverage Report

- **生成时间 / Generated at**：2026-09-06 11:03:17
- **环境 / Environment**：MINGW64_NT-10.0-19045 / java version "17.0.9" 2023-10-17 LTS
- **覆盖范围 / Scope**：库模块单元测试（JaCoCo，基于 target/site/jacoco/jacoco.csv）
  Library module unit tests (JaCoCo, based on target/site/jacoco/jacoco.csv)
- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）
  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)
- **汇总 / Summary**：行/Line 92.3% · 分支/Branch 82.0% · 指令/Instr 92.1% · 方法/Method 93.4%

## 一、覆盖率总览 / 1. Coverage Overview

| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |
|---|---|---|---|---|---|---|---|---|---|
| spring-web | 92.3% | 6282/6805 | 83.7% | 2833/3386 | 91.6% | 93.9% | 205 | ≥90% | ✅ |
| spring-web-view | 96.7% | 323/334 | 78.8% | 134/170 | 97.0% | 99.0% | 15 | ≥80% | ✅ |
| spring-web-support | 93.2% | 2422/2598 | 81.8% | 761/930 | 93.4% | 93.1% | 86 | ≥80% | ✅ |
| spring-web-batch | 91.0% | 345/379 | 79.6% | 109/137 | 92.0% | 94.6% | 18 | ≥80% | ✅ |
| spring-web-websocket | 91.2% | 1026/1125 | 81.2% | 350/431 | 91.9% | 95.5% | 26 | ≥80% | ✅ |
| spring-boot-starter-web | 90.4% | 1097/1213 | 74.7% | 414/554 | 91.1% | 87.1% | 49 | ≥80% | ✅ |
| **汇总 / Total** | **92.3%** | **11495/12454** | **82.0%** | **4601/5608** | **92.1%** | **93.4%** | | | |

## 二、测试规模 / 2. Test Count

| 模块 / Module | 用例数 / Tests |
|---|---|
| spring-web | 2054 |
| spring-web-view | 57 |
| spring-web-support | 825 |
| spring-web-batch | 83 |
| spring-web-websocket | 170 |
| spring-boot-starter-web | 262 |
| **库模块合计 / Library total** | **3451** |
| E2E spring-web-test | 259 |
| E2E spring-web-support-test | 168 |
| **E2E 合计 / E2E total** | **427** |

> 说明 / Note：E2E 用例默认不随本脚本执行，上表基于已存在的 surefire 报告；如需刷新请单独运行
> E2E tests are not run by this script; the table above reflects existing surefire reports.
> 刷新命令 / To refresh: mvn test -pl spring-web-test,spring-web-support-test

## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)

### spring-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Http2ChannelInitializer.new ChannelInboundHandlerAdapter() {...} | 41 | 42 | 2.4% |
| Http2ChannelInitializer.Http2OrHttp1Handler | 34 | 34 | 0.0% |
| BizPoolRegistry | 25 | 140 | 82.1% |
| ResponseStatusExceptionAdapter | 18 | 35 | 48.6% |
| DispatcherHandler | 16 | 159 | 89.9% |
### spring-web-view

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| ViewReturnValueResolver | 5 | 63 | 92.1% |
| RedirectView | 2 | 35 | 94.3% |
| BeetlViewResolver | 2 | 21 | 90.5% |
| ViewResolverRegistry | 1 | 41 | 97.6% |
| FreemarkerViewResolver | 1 | 20 | 95.0% |
### spring-web-support

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| PerfHttpServletRequest | 19 | 288 | 93.4% |
| SupportDispatcherHandler.SessionFlushListener | 14 | 17 | 17.6% |
| PerfServletContext | 13 | 219 | 94.1% |
| WebMvcConfigurer | 11 | 19 | 42.1% |
| SseEmitter | 11 | 23 | 52.2% |
### spring-web-batch

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| DisruptorQueue | 22 | 83 | 73.5% |
| NoOpBatchMetrics | 3 | 8 | 62.5% |
| BufferingBatchHandler | 3 | 49 | 93.9% |
| BatchScanner | 3 | 97 | 96.9% |
| BatchRequest | 2 | 13 | 84.6% |
### spring-web-websocket

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| WebSocketRoutingHandler | 46 | 250 | 81.6% |
| NettyWebSocketSession | 19 | 116 | 83.6% |
| JsrCodecRegistry | 7 | 110 | 93.6% |
| JsrEndpointWebSocketHandler | 6 | 126 | 95.2% |
| JsrEndpointConfigAdapter.new ServerEndpointConfig.Configurator() {...} | 5 | 7 | 28.6% |
### spring-boot-starter-web

| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |
|---|---|---|---|
| Boot4WebServerInitializedEventBridge.new ClassWriter() {...} | 14 | 15 | 6.7% |
| ManagementNettyHttpServer | 10 | 58 | 82.8% |
| ManagementNettyHttpServer.new ChannelInitializer() {...} | 10 | 11 | 9.1% |
| PerfApplicationFactory | 9 | 64 | 85.9% |
| SpringWebSupportAutoConfiguration | 7 | 61 | 88.5% |

