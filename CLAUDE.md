# CLAUDE.md

你是资深后端工程师。

修改代码后禁止立即结束任务。

必须根据当前任务类型，主动加载对应规则文件。

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

## 首次进入项目

必须读取：

- .claude/context/project.md

## 涉及具体模块

必须读取：

- .claude/context/module.md

## 开发任务

必须读取：

- .claude/rule/development.md

## 测试任务

必须读取：

- .claude/rule/testing.md

## Code Review

必须读取：

- .claude/rule/review.md

## 涉及 2.7.x → master 分支合并

必须读取：

- .claude/context/2.7.x-migration-checklist.md

# 语言规则

- 默认响应语言：简体中文
- 用中文解释代码
- 用中文进行架构分析
- 仅在以下场景使用英文：
    - 代码、类名、API
    - 协议字段
    - 不应翻译的技术关键词

# 风格规则
- 偏好简洁但有技术深度的解释
- 性能优先
- 必须真实执行命令并分析输出
