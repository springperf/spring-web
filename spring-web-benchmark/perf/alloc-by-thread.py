#!/usr/bin/env python3
"""按线程归集 JFR ObjectAllocationSample 的分配权重。

用法: python alloc-by-thread.py <jfr文件> [线程过滤子串]

`jfr print` 的事件块格式（--stack-depth 1）:

    jdk.ObjectAllocationSample {
      startTime = ...
      objectClass = X (classLoader = Y)
      weight = N U
      eventThread = "name" (javaThreadId = N)
      stackTrace = [
        frame line: N
      ]
    }

两个必须注意的解析陷阱（都曾导致「服务端分配」被算成客户端的）：
1. `objectClass` 的正则若用 `[^\\(]+`，会在 `(classLoader = ...)` 处截断但留下尾部空格，
   且若写成 `objectClass = X (classLoader` 之外的形态会漏配 —— 一律用显式的
   `objectClass = ([^(]+?)\\s*\\(classLoader`。
2. **不能靠「相邻行」判断字段归属**：块内还有 startTime / stackTrace 等行。
   必须按 `jdk.ObjectAllocationSample {` 开启新块、`eventThread` 出现来收束，
   否则上一个事件的 cls/weight 会泄漏到下一个事件（曾导致 443 MB 的客户端
   ArrayList 被记到服务端头上）。
"""
import collections
import re
import sys

UNIT = {'bytes': 1, 'kB': 1024, 'MB': 1048576, 'GB': 1073741824}

RE_OBJ_START = re.compile(r'jdk\.ObjectAllocationSample \{')
RE_CLASS = re.compile(r'objectClass = ([^(]+?)\s*\(classLoader')
RE_WEIGHT = re.compile(r'weight = ([\d.]+)\s*(\w+)')
RE_THREAD = re.compile(r'eventThread = "([^"]*)"')


def parse(path):
    """返回 {线程名: Counter(类 -> 权重)}。"""
    buckets = collections.defaultdict(lambda: collections.Counter())
    in_block = False
    cls = None
    weight = None
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            if RE_OBJ_START.search(line):
                in_block, cls, weight = True, None, None
                continue
            if not in_block:
                continue
            m = RE_CLASS.search(line)
            if m:
                cls = m.group(1).strip()
                continue
            m = RE_WEIGHT.search(line)
            if m and weight is None:
                weight = float(m.group(1)) * UNIT[m.group(2)]
                continue
            m = RE_THREAD.search(line)
            if m and cls and weight is not None:
                buckets[m.group(1)][cls] += weight
                in_block = False
    return buckets


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    filt = sys.argv[2] if len(sys.argv) > 2 else 'EventLoopGroup'

    all_buckets = parse(path)
    srv = {t: c for t, c in all_buckets.items()
           if filt in t or t.startswith('pool-') or t.startswith('perf-virtual-')}

    if not srv:
        print(f"没有线程名匹配 '{filt}'。已见线程：")
        for t, c in all_buckets.items():
            print(f"  {t}  ({sum(c.values()) / 1048576:.2f} MB)")
        return

    total = collections.Counter()
    for c in srv.values():
        total.update(c)
    grand = sum(total.values())

    print(f"线程过滤: '{filt}'")
    for t, c in srv.items():
        print(f"  {t}  ({sum(c.values()) / 1048576:.2f} MB, {sum(c.values()) * 100 / grand:.1f}%)")
    print(f"\n分配采样总权重: {grand / 1048576:.2f} MB")
    print(f"\n{'类':<58}{'权重(MB)':>11}{'占比':>8}")
    print("-" * 77)
    for k, v in total.most_common(20):
        print(f"{k:<58}{v / 1048576:>11.2f}{v * 100 / grand:>7.1f}%")


if __name__ == '__main__':
    main()
