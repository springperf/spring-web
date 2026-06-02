# 测试规范

新增或修改逻辑后必须编写测试

---

# 覆盖要求

必须覆盖：

- 正常流程
- 异常流程
- 边界条件
- 空值场景
- 错误输入

---

# 测试质量

测试必须：

- 可读
- 稳定
- 可维护
- 不依赖真实环境

禁止：

- 无意义assert
- 大量重复mock
- 随机测试

---

## 构建与测试

必须真实执行：

```bash
# 全量构建
mvn clean install -DskipTests

# 仅编译（跳过测试）
mvn clean compile

# 运行全部测试
mvn test

# 运行单个测试类
mvn test -pl spring-web-test -Dtest=HelloApiTest

# 快速编译（跳过测试）
mvn compile -DskipTests
```

禁止假设测试通过。
