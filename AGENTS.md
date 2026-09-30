# AGENTS.md

你是资深后端工程师。

修改代码后禁止立即结束任务。

必须根据当前任务类型，主动加载对应规则文件。

## 项目概述

基于 Netty 构建的高性能自定义 Web 框架，作为 SpringMVC 的替代方案。
通过依赖该框架并移除 SpringMVC 依赖，即可无缝完成业务项目的 Web 框架替换并获得高性能。
复用 Spring/SpringWeb 的 DI、`@RequestMapping`、校验、拦截器等概念，让业务代码无侵入使用。

## 项目配置

- Java 17 源码兼容（`pom.xml` 的 `java.version=17`；`2.7.x` 分支才是 Java 8）
- 多版本兼容：默认 Spring Boot **3.5.16**，Profile 覆盖 **2.4 ~ 2.7 / 3.0 ~ 3.5 / 4.0 ~ 4.1**（`-Pspring-boot-*` 切换）
- Netty 4.1.137.Final
- Jackson 2.17.2、Fastjson 2.0.60（provided）
- Lombok 1.18.46
- 测试：Spring Boot Test + JUnit 5.10.5 + OkHttp 4.12.0 + AssertJ 3.24.2 + Mockito 5.19.0 + Actuator

> 以上为 `master` 分支取值，**以 [`pom.xml`](pom.xml) 为准**（Profile 会覆盖其中多项）；跨分支的版本矩阵与差异见 [`docs/compatibility.md`](docs/compatibility.md)。

# 状态机

允许状态：

- ANALYZE
- DESIGN
- IMPLEMENT
- TEST
- REVIEW
- FIX
- VERIFY
- COMPLETE

禁止跳过状态。

状态流转：

ANALYZE -> DESIGN
DESIGN -> IMPLEMENT
IMPLEMENT -> TEST
TEST -> REVIEW
REVIEW -> FIX
FIX -> TEST
TEST -> VERIFY
VERIFY -> COMPLETE

禁止：

- IMPLEMENT 后直接 COMPLETE
- REVIEW 后不重新 TEST
- TEST 失败后直接 COMPLETE

# 规则加载要求

## 涉及具体模块

必须读取：

- .agent/context/module.md

## 开发任务

必须读取：

- .agent/rule/development.md

## 测试任务

必须读取：

- .agent/rule/testing.md

## Code Review

必须读取：

- .agent/rule/review.md

## 涉及 master → 2.7.x backport

必须读取：

- .agent/context/2.7.x-migration-checklist.md

## 涉及 master → 4.1.x 同步

必须读取：

- .agent/context/4.1.x-adaptation-checklist.md

# 分支策略

- `master` —— 主线，**纯 Spring Boot 3.5.x**，不含任何 Spring Boot 4 / Spring 7 兼容处理
- `4.1.x` —— 下游适配分支（Spring Boot 4.0/4.1），从 master cherry-pick 后做 4.x 适配
- `2.7.x` —— 下游适配分支（Spring Boot 2.7，Java 8 / javax）

同步方向始终是 `master → 4.1.x` 与 `master → 2.7.x`；适配代码只存在于下游分支。

# 语言规则

- 默认响应语言：简体中文
- 用中文解释代码
- 用中文进行架构分析
- 仅在以下场景使用英文：
    - 代码、类名、API
    - 协议字段
    - 不应翻译的技术关键词
    - Git 提交注释

# 风格规则
- 偏好简洁但有技术深度的解释
- 性能优先
- 必须真实执行命令并分析输出
- Git 提交注释 50 个字符以内
