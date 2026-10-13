#!/usr/bin/env python3
"""解析 -XX:+PrintInlining 输出，统计 springperf 方法的内联决策。

用法: python parse-inlining.py <inlining 输出文件>

PrintInlining 的输出是**多行混排**的（同一决策的原因与结果可能分属不同行，
且被 ANSI 控制符与回车符干扰），故按「决策原因」关键字做行内提取，
再把方法签名与原因配对。

输出分三部分：
  1. 内联失败明细（按出现次数）—— 这是优化目标
  2. 失败原因分布
  3. 成功内联的热方法（供对照）
"""
import collections
import re
import sys

# PrintInlining 的失败原因关键字
FAIL = ('hot method too large', 'callee is too large', 'not inlineable',
        'never executed', 'too deep', 'not compilable', 'too big',
        'no static binding', 'no receiver')

# 匹配 "  Klass::method (N bytes)"，允许中间有 ANSI/回车噪声
SIG = re.compile(r'(io\.springperf\.[A-Za-z0-9_.$]+)::([A-Za-z0-9_$]+)\s*\((\d+)\s*bytes?\)')
BYTE = re.compile(r'\((\d+)\s*bytes?\)')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    text = open(sys.argv[1], encoding='utf-8', errors='replace').read()
    # PrintInlining 每条决策是**单行**：`... @ N Klass::method (M bytes)   <结果>`
    # 其中结果可能是 "inline (hot)" / "hot method too large" / "not inlineable (...)" 等。
    # 关键：原因与签名在同一行内 —— 不能跨行配对，否则会把下一条决策的失败原因
    # 误安到本条方法上（曾导致 getRequestContext(2B) 这类显然可内联的方法被列为失败）。
    # 用 \r 拆分以免同一行的重写丢失最新状态。
    fail_sig = collections.Counter()
    ok_sig = collections.Counter()
    reasons = collections.Counter()
    for raw in text.replace('\r', '\n').split('\n'):
        if 'io.springperf' not in raw:
            continue
        m = SIG.search(raw)
        if not m:
            continue
        # 签名之后的部分才是结果区
        tail = raw[m.end():]
        sig = f'{m.group(1)}::{m.group(2)} ({m.group(3)} B)'
        reason = next((f for f in FAIL if f in tail), None)
        if reason:
            fail_sig[sig] += 1
            reasons[reason] += 1
        elif re.search(r'\binline\b', tail):
            ok_sig[sig] += 1

    print(f'内联失败（{sum(fail_sig.values())} 次决策，{len(fail_sig)} 个方法）\n')
    print(f'{"方法":<66}{"字节":>7}{"次数":>6}')
    print('-' * 79)
    for sig, n in fail_sig.most_common(25):
        size = sig.rsplit('(', 1)[1].rstrip(' B)')
        print(f'{sig:<66}{size:>7}{n:>6}')

    print(f'\n失败原因分布（共 {sum(reasons.values())} 次）')
    for r, n in reasons.most_common():
        print(f'  {r:<32}{n:>6}')

    print(f'\n成功内联（{sum(ok_sig.values())} 次，前 12）')
    for sig, n in ok_sig.most_common(12):
        print(f'  {sig:<66}{n:>5}')


if __name__ == '__main__':
    main()
