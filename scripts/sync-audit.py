#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sync-audit.py — 分支同步审计：只报告「可自动判定」的差异，不下同步结论。

用法:
    python3 scripts/sync-audit.py --target 2.7.x
    python3 scripts/sync-audit.py --target 4.1.x --scope all --limit 300
    python3 scripts/sync-audit.py --target 2.7.x --output docs/feature/branch-sync-2.7x.md
    python3 scripts/sync-audit.py --target 2.7.x --whitelist docs/feature/sync-whitelist.txt

退出码: 0 = 报告已生成（无论差异多少）；2 = 用法/环境错误

为什么不做 CI 阻断:
    同步是「适配式同步」——同一行为在下游用 javax / 旧 API / 自有抽象重写是常态，
    「文件内容不同」不等于「漏同步」。能自动判定的只有两类硬事实：
      1. base 有、target 完全没有该文件（漏同步候选，误报率最低）；
      2. target 有、base 没有（反向分叉，需要解释）。
    因此本脚本只产出报告，白名单内的路径直接过滤，剩下的交人工逐项定性。

报告的排序原则:
    「仅 base 有」按目录分组（新增模块会整片出现，一眼能看出是模块级缺口）；
    「同名差异」按增删行数倒序（行数少的多半是版本适配，行数多的多半是功能没同步）。
"""

import argparse
import os
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCOPES = ('main', 'test', 'all')

# 路径匹配在 Python 侧做：仓库里的路径都带模块前缀（spring-web/src/main/...），
# 直接用 `startswith('src/main/')` 会一个都匹配不到。
PATHSPECS = {
    'main': ['*src/main/*'],
    'test': ['*src/test/*'],
    'all': ['*src/main/*', '*src/test/*', 'pom.xml', '*pom.xml'],
}


def in_scope(path, scope):
    marked = '/' + path
    if scope == 'main':
        return '/src/main/' in marked
    if scope == 'test':
        return '/src/test/' in marked
    return ('/src/main/' in marked or '/src/test/' in marked
            or path == 'pom.xml' or path.endswith('/pom.xml'))


def git(*args, check=True):
    proc = subprocess.run(['git', '-c', 'core.quotepath=false'] + list(args),
                          cwd=ROOT, capture_output=True)
    if check and proc.returncode != 0:
        raise RuntimeError('git %s failed: %s' % (' '.join(args),
                                                 proc.stderr.decode('utf-8', 'replace').strip()))
    return proc.stdout.decode('utf-8', 'replace')


def rev_exists(rev):
    return subprocess.run(['git', 'rev-parse', '--verify', '--quiet', rev],
                          cwd=ROOT, capture_output=True).returncode == 0


def list_files(rev, scope):
    out = git('ls-tree', '-r', '--name-only', rev)
    return [p for p in out.split('\n') if p and in_scope(p, scope)]


def load_whitelist(path):
    if not path or not os.path.isfile(path):
        return []
    patterns = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith('#'):
                patterns.append(line)
    return patterns


def whitelisted(path, patterns):
    from fnmatch import fnmatch
    return any(fnmatch(path, p) for p in patterns)


def numstat(base, target, scope):
    """返回 {path: (added, deleted)}，只统计两边都存在的同名文件。"""
    out = git('diff', '--numstat', '--no-renames', base, target, '--', *PATHSPECS[scope])
    stats = {}
    for line in out.split('\n'):
        parts = line.split('\t')
        if len(parts) != 3:
            continue
        added, deleted, path = parts
        if added == '-' or deleted == '-':  # 二进制
            continue
        stats[path] = (int(added), int(deleted))
    return stats


def group_by_dir(paths):
    """按「模块/包」两级归组，便于一眼看出整片缺口。"""
    groups = defaultdict(list)
    for p in paths:
        parts = p.split('/')
        if len(parts) >= 3 and parts[0].startswith('spring-'):
            key = '/'.join(parts[:2])          # 例如 spring-web/src
        else:
            key = parts[0]
        groups[key].append(p)
    return groups


def write_report(args, base_rev, base_only, target_only, diffs, filtered_count, out_path):
    lines = []
    lines.append('# 分支同步审计：%s → %s' % (args.base, args.target))
    lines.append('')
    lines.append('> 本报告由 `scripts/sync-audit.py` 生成，只列**可自动判定**的差异，不代表「必须同步」。')
    lines.append('> 同步是适配式同步：同一行为在下游用 javax / 旧 API / 自有抽象实现是常态。')
    lines.append('')
    lines.append('- 生成时间：%s' % datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
    lines.append('- 基线：`%s` = `%s`' % (args.base, base_rev))
    lines.append('- 目标：`%s` = `%s`' % (args.target, git('rev-parse', '--short', args.target).strip()))
    lines.append('- 范围：`%s`' % args.scope)
    lines.append('- 白名单：%s（过滤掉 %d 个路径）'
                 % ('`%s`' % args.whitelist if args.whitelist else '无', filtered_count))
    lines.append('')
    lines.append('## 汇总')
    lines.append('')
    lines.append('| 类别 | 数量 |')
    lines.append('|------|------|')
    lines.append('| 仅 base 有（漏同步候选） | %d |' % len(base_only))
    lines.append('| 仅 target 有（反向分叉） | %d |' % len(target_only))
    lines.append('| 同名文件有差异 | %d |' % len(diffs))
    lines.append('')

    lines.append('## 1. 仅 base 有、target 完全没有（漏同步候选）')
    lines.append('')
    if not base_only:
        lines.append('无。')
    else:
        groups = group_by_dir(base_only)
        lines.append('按目录分组（数量降序）：')
        lines.append('')
        lines.append('| 目录前缀 | 文件数 |')
        lines.append('|---------|--------|')
        for key, items in sorted(groups.items(), key=lambda kv: -len(kv[1])):
            lines.append('| `%s` | %d |' % (key, len(items)))
        lines.append('')
        lines.append('明细（最多 %d 条）：' % args.limit)
        lines.append('')
        for p in base_only[:args.limit]:
            lines.append('- `%s`' % p)
        if len(base_only) > args.limit:
            lines.append('- …其余 %d 条省略' % (len(base_only) - args.limit))
    lines.append('')

    lines.append('## 2. 仅 target 有、base 没有（反向分叉，需要解释）')
    lines.append('')
    if not target_only:
        lines.append('无。')
    else:
        for p in target_only[:args.limit]:
            lines.append('- `%s`' % p)
        if len(target_only) > args.limit:
            lines.append('- …其余 %d 条省略' % (len(target_only) - args.limit))
    lines.append('')

    lines.append('## 3. 同名文件差异（按增删行数倒序，前 %d 条）' % args.limit)
    lines.append('')
    lines.append('> 行数只用于排序，不用于判定：适配式改写可能整片不同，也可能只差一个 import。')
    lines.append('')
    if not diffs:
        lines.append('无。')
    else:
        lines.append('| 文件 | + | - |')
        lines.append('|------|---|---|')
        for path, (added, deleted) in diffs[:args.limit]:
            lines.append('| `%s` | %d | %d |' % (path, added, deleted))
        if len(diffs) > args.limit:
            lines.append('')
            lines.append('…其余 %d 个文件省略' % (len(diffs) - args.limit))
    lines.append('')

    text = '\n'.join(lines)
    if out_path:
        os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
        with open(out_path, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text + '\n')
        print('报告已写入: %s' % out_path)
    else:
        print(text)


def main(argv):
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument('--base', default='master')
    parser.add_argument('--target', required=True)
    parser.add_argument('--scope', default='main', choices=SCOPES)
    parser.add_argument('--whitelist', default=None)
    parser.add_argument('--output', default=None)
    parser.add_argument('--limit', type=int, default=200)
    args = parser.parse_args(argv)

    for rev in (args.base, args.target):
        if not rev_exists(rev):
            print('错误: 找不到修订版本 %r（先 git fetch，或检查分支名）' % rev, file=sys.stderr)
            return 2

    base_files = set(list_files(args.base, args.scope))
    target_files = set(list_files(args.target, args.scope))
    patterns = load_whitelist(args.whitelist)

    raw_base_only = sorted(base_files - target_files)
    base_only = [p for p in raw_base_only if not whitelisted(p, patterns)]
    filtered = len(raw_base_only) - len(base_only)

    target_only = sorted(p for p in (target_files - base_files) if not whitelisted(p, patterns))

    stats = numstat(args.base, args.target, args.scope)
    diffs = sorted(((p, v) for p, v in stats.items()
                    if not whitelisted(p, patterns) and (v[0] or v[1])),
                   key=lambda kv: -(kv[1][0] + kv[1][1]))

    write_report(args, git('rev-parse', '--short', args.base).strip(),
                 base_only, target_only, diffs, filtered, args.output)

    # 控制台摘要（报告写文件时也打，便于快速判断）
    if args.output:
        print('仅 base 有: %d（白名单过滤 %d）| 仅 target 有: %d | 同名差异: %d'
              % (len(base_only), filtered, len(target_only), len(diffs)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
