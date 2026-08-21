## 开发规范

### 组件通过 LifecycleWebComponent 自初始化
持有资源（线程池、缓存等）的组件必须继承 `BaseWebComponent`，通过三阶段生命周期（`initWithWebContext` → `initComponentPhase1/2/3` → `destroyComponent`）自行初始化。

### 各组件职责清晰
写代码时需考虑这段代码逻辑应该放在哪个类里，代码应该优先放在对应模块中，如果不知道放在该模块下的哪个文件，优先放在对应的`xxxRegistry`中

### 启动时预解析元数据
影响请求处理的注解（如 `@RunInPool`）必须初始化阶段一次性解析，通过`MappingCacheKey`缓存(参照`WebDataBinderRegistry`用法)。请求路径上零反射、零注解查找。

### 启动时 fail-fast 校验
错误配置必须在启动时以明确的 `IllegalStateException` 中断，示例如下：
- `@RunInPool("io")` 引用了不存在的线程池名称
- 控制器参数缺少对应的参数解析器（当 `server.check-on-startup=true` 时）
  配置明确错误时，禁止静默降级为其他行为。

### 修改范围控制

修改必须：
- 最小化
- 局部化
- 易Review
- 易回滚

---

### 方法设计
- 单一职责
- 避免超长方法
- 避免重复代码

---

### 异常处理
- 不允许吞异常
- 异常必须带上下文

---

### 异常统一收敛到 ExceptionRegistry
框架代码不抛异常给 Netty，不依赖 Netty 的异常处理机制。在 `DispatcherHandler.doHandle()` 中 `catch Throwable` 并交给 `ExceptionRegistry`。

### 避免隐式对象创建
请求路径上避免创建短期临时对象（如每次请求 new ArrayList）、避免装箱、避免反射。启动阶段一次性完成所有元数据解析和缓存。
