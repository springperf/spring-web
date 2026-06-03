# 贡献指南

感谢您对 perf-spring-web 的关注！我们欢迎各种形式的贡献——提交 Bug、提议新功能、改进文档或提交代码。

## 行为准则

请阅读并遵守我们的 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## 如何贡献

### 报告 Bug

1. 在 [Issues](https://github.com/springperf/spring-web/issues  ) 中搜索是否已有相同问题
2. 如果没有，创建新 Issue 并选择 Bug Report 模板
3. 请包含：
   - 运行环境（JDK 版本、操作系统）
   - 复现步骤
   - 期望行为和实际行为
   - 相关日志或堆栈信息

### 提议新功能

1. 在 [Issues](https://github.com/springperf/spring-web/issues  ) 中搜索是否已有类似提议
2. 创建 Feature Request Issue，描述：
   - 使用场景
   - 期望 API 或行为
   - 是否愿意参与实现

### 提交代码

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交代码：
   - 遵循现有代码风格
   - 为核心公共接口添加 JavaDoc（英文）
   - 为新功能添加测试
   - 确保 `mvn clean test` 通过
4. 提交 Pull Request
5. 等待 Code Review

### 代码规范

- Java 8 兼容
- 遵循 Spring Framework 的命名约定
- 公共 API 必须有英文 JavaDoc
- 中文注释用于复杂的业务逻辑说明
- 包命名：`io.github.spring.web.*`

### PR 提交检查清单

- [ ] 代码编译通过：`mvn clean compile`
- [ ] 测试通过：`mvn clean test`
- [ ] 已添加或更新相关测试
- [ ] 公共 API 已添加 JavaDoc
- [ ] 无空 catch 块（资源清理场景除外）
- [ ] 无 System.out.println 调试代码
