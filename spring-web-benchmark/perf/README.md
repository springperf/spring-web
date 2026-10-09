# perf/ —— 性能分析脚本

配套文档：`docs/feature/performance-analysis-methodology.md`
（方法论；该目录未纳入 git 跟踪）

## 前置

```bash
# 1. 编译 + 生成 classpath（脚本依赖 target/cp-perf.txt）
cd <repo>
mvn -o -pl spring-web-benchmark -Pbenchmark-perf compile

# 2. WSL 模式还需：WSL 内装 JDK 17 + 配置 .wslconfig
./spring-web-benchmark/perf/wsl-check.sh
```

## 用法速查

一键挂机（睡前跑，三阶段都不含在内中断）：`overnight.sh`，见下节。

```bash
cd spring-web-benchmark/perf

# 分配量（唯一可用于版本对比的指标，离散 ~0.6%）
./measure-alloc.sh <标签> [端口]

# 吞吐对比前必做：测当前口径的噪声
./heap-noise.sh 1g 6   # 先看该口径能分辨多大的差异

# 吞吐 ABBA（WSL 分离，唯一可信口径；30 轮约 78 分钟）
export BASELINE_WT=<基线 worktree 路径>   # 不设则只测当前版
./wsl-abba2.sh 6      # 追加模式，可多次运行累积

# CPU 侧：内联观测
./inlining-ab.sh 4
"$JDK17/bin/java" -cp ... -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining ... > inlining.txt
python parse-inlining.py inlining.txt

# JFR 分配分析
python alloc-by-thread.py <导出的 jfr 文本>   # 按线程归因
python alloc-top.py <导出的 jfr 文本>         # 分配热点Top N
python alloc-diff.py <base.txt> <cur.txt>     # 两版本差值
```

## 脚本清单

| 脚本 | 用途 |
|------|------|
| `overnight.sh` | 挂机入口，串起下面三个阶段 |
| `measure-alloc.sh` | 服务端分配量（TLAB 精确计数） |
| `heap-noise.sh` | 测当前口径的噪声 sd，判断能否分辨目标差异 |
| `wsl-abba2.sh` | WSL 分离吞吐 ABBA，含三条判定 |
| `wsl-perf-server.sh` | WSL 内启停 perf 服务端（支持切 worktree） |
| `wsl-check.sh` | WSL 环境自检 |
| `wsl-jfr.sh` | 录制服务端 JFR |
| `inlining-ab.sh` | JIT 内联观测 |
| `verify-throughput.sh` | 单轮吞吐冒烟（连通性验证，不出结论） |
| `noise-probe.sh` | 快速探测当前机器的噪声水平 |
| `perf-env.sh` | 共用环境变量（被其余脚本 source） |
| `parse-inlining.py` | 解析 `PrintInlining` 输出 |
| `alloc-by-thread.py` / `alloc-top.py` / `alloc-diff.py` | JFR 分配分析 |

> `verify-throughput.sh` 与 `noise-probe.sh` 是快速探查手段，**不能代替 `wsl-abba2.sh` 出结论**。

##挂机跑（overnight.sh）

一次跑完三个阶段，**任何一步失败都记录但继续**，不会因为服务端起不来就整夜中断。
日志与结果都落在 `target/perf-reports/`。

```bash
cd spring-web-benchmark/perf
export BASELINE_WT=/d/workspace/springperf/baseline-41   # 不设则跳过所有基线组
./overnight.sh                # 全阶段：alloc thrpt noise
./overnight.sh alloc          # 只跑分配量（~3 min，最可信）
./overnight.sh thrpt          # 只跑吞吐 ABBA（每轮 ~2.6 min）
OVERNIGHT_ROUNDS=30 ./overnight.sh thrpt   # 覆盖默认 18 轮
```

阶段含义：

| 阶段 | 内容 | 耗时 | 用途 |
|---|---|---|---|
| `alloc` | 当前版 + 基线版全端点分配量 | ~3 min | **版本对比唯一可信指标**（离散~0.6%） |
| `thrpt` | WSL 分离吞吐 ABBA，逐端点 json/get/bytes | 18 轮 ≈ 47 min | 吞吐结论，三条判定标准缺一不可 |
| `noise` | 固定 1g 堆重复 6 轮测 sd | ~15 min | 写进结论，说明该口径能分辨多大差异 |

结果文件：

| 文件 | 内容 |
|---|---|
| `server-alloc-cur-all.json` / `-base-all.json` | 各端点 `median` B/op + 5 轮 `samples` |
| `wsl-abba-cur.txt` / `-base.txt` | 追加格式，`端点 ops/s` 每行一条 |
| `heap-1g.txt` | 噪声测量原始输出 |
| `overnight-*.log` | 完整日志（含每阶段成败） |

早上看结果：吞吐统计由 `wsl-abba2.sh` 末尾的 python 自动打印（BASE/CUR 均值、
sd、配对 t、符号检验 p、分段稳定性），直接翻日志尾部即可。

## 配置

所有脚本 `source perf-env.sh`，其中定义可覆盖的环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `JDK17` | 自动探测 | Windows 侧 JDK（跑 JMH 客户端） |
| `SERVLET_JAR` | `D:/maven/repository/.../jakarta.servlet-api-6.1.0.jar` | benchmark pom 里是 provided 作用域，必须显式补 |
| `BASELINE_WT` | 空 | 基线 worktree 路径；不设则 A/B 脚本跳过基线组 |
| `PERF_OUT` | `spring-web-benchmark/target/perf-reports` | 报告输出 |
| `WSL_JAVA_HOME` | `/home/hcd/jdk17` | WSL 侧 JDK |
| `WSL_MVN_REPO` | `/mnt/d/maven/repository` | WSL 侧离线仓库 |

## 已知失效的 JVM 参数

| 参数 | 后果 |
|---|---|
| `-XX:+UseParallelGC` | 结果表为空（与 Netty 不兼容） |
| `-XX:-TieredCompilation` | 预热期过长，短迭代跑不完 |

详见方法论文档 §2.5。
