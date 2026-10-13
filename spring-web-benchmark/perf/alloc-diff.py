#!/usr/bin/env python3
"""对比两份 JFR 导出文本的服务端分配构成。

用法: python alloc-diff.py <base.txt> <cur.txt>

重要前提：JFR ObjectAllocationSample 的 weight 是**外推值**（单次采样可被放大到
数百 MB），故**不可跨版本比较绝对值**。本脚本只比较「类别的相对构成占比」，
即哪些分配点是当前版新出现的。
"""
import collections
import re
import sys

UNIT = {'bytes': 1, 'kB': 1024, 'MB': 1048576, 'GB': 1073741824}
RE_OBJ = re.compile(r'jdk\.ObjectAllocationSample \{')
RE_CLS = re.compile(r'objectClass = ([^(]+?)\s*\(classLoader')
RE_W = re.compile(r'weight = ([\d.]+)\s*(\w+)')
RE_T = re.compile(r'eventThread = "([^"]*)"')
# 服务端线程的识别子串（不是前缀！Netty 的线程名是 nioEventLoopGroup-3-1，
# EventLoopGroup 出现在中间）。曾因误用 startswith 导致过滤全部落空。
SERVER_SUBSTR = ('EventLoopGroup', 'pool-', 'perf-virtual-')


def is_server(thread):
    return any(s in thread for s in SERVER_SUBSTR)


def parse(path):
    buckets = collections.defaultdict(lambda: collections.Counter())
    inb, cls, wt = False, None, None
    for line in open(path, encoding='utf-8', errors='replace'):
        if RE_OBJ.search(line):
            inb, cls, wt = True, None, None
            continue
        if not inb:
            continue
        m = RE_CLS.search(line)
        if m:
            cls = m.group(1).strip()
            continue
        m = RE_W.search(line)
        if m and wt is None:
            wt = float(m.group(1)) * UNIT[m.group(2)]
            continue
        m = RE_T.search(line)
        if m and cls and wt is not None:
            if is_server(m.group(1)):
                buckets[m.group(1)][cls] += wt
            inb = False
    total = collections.Counter()
    for c in buckets.values():
        total.update(c)
    return total


def main():
    base = parse(sys.argv[1])
    cur = parse(sys.argv[2])
    bsum, csum = sum(base.values()), sum(cur.values())
    print(f"base 采样权重 {bsum/1048576:.1f} MB   cur {csum/1048576:.1f} MB")
    print("（weight 是外推值，绝对值不可比；以下只看构成差异）\n")

    keys = sorted(set(base) | set(cur),
                  key=lambda k: -(cur.get(k, 0) / csum - base.get(k, 0) / bsum))
    print(f"{'类':<58}{'base占比':>10}{'cur占比':>10}{'差':>10}")
    print("-" * 88)
    shown = 0
    for k in keys:
        bp = base.get(k, 0) / bsum * 100
        cp = cur.get(k, 0) / csum * 100
        d = cp - bp
        if abs(d) < 1.0:
            continue
        tag = '  <== 基线独有' if bp > 3 and d < 0 else ('  <== 当前版新增' if cp > 3 and d > 0 else '')
        print(f"{k:<58}{bp:>9.1f}%{cp:>9.1f}%{d:>+9.1f}%{tag}")
        shown += 1
        if shown >= 16:
            break


if __name__ == '__main__':
    main()
