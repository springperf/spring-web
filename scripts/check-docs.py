#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-docs.py — 校验受 Git 管理的 Markdown 文档：坏链、锚点、编码与基础格式。

用法:
    python3 scripts/check-docs.py                 # 校验全部受管理 *.md
    python3 scripts/check-docs.py --self-test     # 自检：验证锚点规则与检出能力（不读仓库）
    python3 scripts/check-docs.py --quiet         # 只输出汇总
    python3 scripts/check-docs.py -v              # 同时列出每个文件的检查结果

退出码: 0 = 通过；1 = 存在缺陷；2 = 用法/环境错误

锚点规则（**必须与 GitHub 一致**，本仓库两次栽在这里，勿凭直觉改）:
    GitHub 的 heading anchor 由 github-slugger 生成：
      1) 去掉行内代码反引号、加粗标记，保留链接文字；
      2) 转小写；
      3) **删除**所有标点与符号（删除后**不补 `-`**）——包括 ASCII 标点与
         U+00B7 `·`（落在其正则区间 \\xB6-\\xB9）、U+3001 `、`（\\u2E30-\\u3004）、
         U+FF1A `：`（\\uFF1A-\\uFF20）、全角括号、破折号 `—`、箭头 `→` 等；
      4) 空白字符替换为 `-`；连字符 `-` 与下划线 `_` 原样保留。
    正例: 标题 `七、背压机制：\\`WriteWaterMark\\` + \\`BackpressureHandler\\``
          → `七背压机制writewatermark--backpressurehandler`（`、`/`：`/`+` 删除，" + " 剩两个空格 → 两个 `-`）
    反例: 把 `#原则-3--避免…` 改成 `#原则-3-·-避免…` 会制造坏锚点——`·` 是被删除的字符。
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

# ---------------- 锚点（GitHub 规则） ----------------

_PUNCT = re.compile(r'[^\w\s-]', re.UNICODE)


def github_slug(heading):
    """按 github-slugger 规则计算 heading 锚点（见文件头说明）。"""
    h = heading.replace('`', '')
    h = re.sub(r'\*\*|\*', '', h)
    h = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', h)   # [text](url) -> text
    h = _PUNCT.sub('', h.strip().lower())            # 删标点/符号（含 · 、 ： — → 等）
    return h.replace(' ', '-')


# ---------------- 解析 ----------------

FENCE = re.compile(r'^\s*(```|~~~)')
HEADING = re.compile(r'^(#{1,6})\s+(.*?)\s*$')
MD_LINK = re.compile(r'\[([^\]]*)\]\(([^)\s]+)\)')
HTML_LINK = re.compile(r'(?:src|href)\s*=\s*"([^"]+)"')

# H1 例外：以 `#` 作一级分组是这些文件/目录的既有约定
H1_ALLOWLIST = ('AGENTS.md', 'LICENSE.md', '.agent/', '.github/')


def iter_lines(path):
    """按行产出 (行号, 行内容)，并给出该行是否位于代码块内。CRLF 由通用换行处理。"""
    with open(path, encoding='utf-8') as f:
        text = f.read()
    in_fence = False
    for i, line in enumerate(text.split('\n'), 1):
        is_fence = bool(FENCE.match(line))
        if is_fence:
            in_fence = not in_fence
        yield i, line, in_fence
    return text


def read_text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def slugs_of(path, cache):
    if path not in cache:
        s = set()
        for _, line, in_fence in iter_lines(path):
            if in_fence:
                continue
            m = HEADING.match(line)
            if m:
                s.add(github_slug(m.group(2)))
        cache[path] = s
    return cache[path]


# ---------------- 单项检查 ----------------

def check_links(root, files, checks, verbose):
    """坏链 + 锚点（含同文件 `#x` 与跨文件 `path.md#x`，以及 HTML 的 src/href）。"""
    cache = {}
    for rel in files:
        full = os.path.join(root, rel)
        base = os.path.dirname(full)
        for lineno, line, in_fence in iter_lines(full):
            if in_fence:
                continue
            targets = [m.group(2) for m in MD_LINK.finditer(line)]
            targets += [m.group(1) for m in HTML_LINK.finditer(line)]
            for target in targets:
                if re.match(r'^(https?:|mailto:|data:|//)', target):
                    continue
                if target.startswith('#'):
                    frag = target[1:]
                    if frag and frag not in slugs_of(full, cache):
                        checks.append(('anchor', rel, lineno, target))
                    continue
                path_part, _, frag = target.partition('#')
                if not path_part:
                    continue
                resolved = os.path.normpath(os.path.join(base, path_part))
                if not os.path.exists(resolved):
                    checks.append(('link', rel, lineno, target))
                    continue
                if frag and resolved.endswith('.md'):
                    if frag not in slugs_of(resolved, cache):
                        checks.append(('anchor', rel, lineno, target))
        if verbose:
            print('  checked: %s' % rel)


def check_encoding_and_format(root, files, checks, verbose):
    """BOM / 非 UTF-8 / 缺尾换行 / H1 数量 / 行尾单空格（2+ 空格是硬换行语义，仅统计）。"""
    hard_breaks = 0
    for rel in files:
        full = os.path.join(root, rel)
        raw = open(full, 'rb').read()
        if raw.startswith(b'\xef\xbb\xbf'):
            checks.append(('bom', rel, 0, 'UTF-8 BOM'))
        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError as e:
            checks.append(('encoding', rel, 0, 'not valid UTF-8: %s' % e))
            continue
        if text and not text.endswith('\n'):
            checks.append(('final-newline', rel, 0, 'missing trailing newline'))
        h1 = 0
        for lineno, line, in_fence in iter_lines(full):
            if not in_fence and line.startswith('# '):
                h1 += 1
            if line.endswith('  ') and line.strip():
                hard_breaks += 1
            elif line != line.rstrip():
                checks.append(('trailing-space', rel, lineno, 'single trailing space'))
        if h1 != 1 and not rel.startswith(H1_ALLOWLIST):
            checks.append(('h1-count', rel, 0, 'H1 count = %d' % h1))
        if verbose:
            print('  checked: %s' % rel)
    return hard_breaks


# ---------------- 自检 ----------------

def self_test():
    """用临时文档验证：GitHub slug 规则 + 坏链/坏锚点检出能力。"""
    tmp = tempfile.mkdtemp(prefix='check-docs-selftest-')
    cases = {
        'target.md': '# Target\n\n## 七、背压机制：`WriteWaterMark` + `BackpressureHandler`\n',
        'good.md': ('# Good\n\n'
                    '## 原则 3 · 避免线程模型僵化\n\n'
                    '[ok](#原则-3--避免线程模型僵化) [ok2](target.md#七背压机制writewatermark--backpressurehandler)\n'),
        'bad.md': ('# Bad\n\n'
                   '[bad-anchor](#原则-3-·-避免线程模型僵化) '
                   '[bad-cross](target.md#七、背压机制：writewatermark-backpressurehandler) '
                   '[bad-link](missing.md)\n'),
    }
    for name, body in cases.items():
        with open(os.path.join(tmp, name), 'w', encoding='utf-8') as f:
            f.write(body)

    # 期望：以上 3 个文件恰好覆盖 2 种规则的正/反例
    from_good = github_slug('原则 3 · 避免线程模型僵化')
    from_bad = github_slug('原则 3 · 避免线程模型僵化'.replace('·', ''))
    assert from_good == '原则-3--避免线程模型僵化', from_good
    assert from_bad == '原则-3--避免线程模型僵化', from_bad
    assert github_slug('七、背压机制：`WriteWaterMark` + `BackpressureHandler`') == \
        '七背压机制writewatermark--backpressurehandler'

    checks = []
    files = ['target.md', 'good.md', 'bad.md']
    check_links(tmp, files, checks, verbose=False)
    kinds = sorted(c[0] for c in checks)
    if kinds != ['anchor', 'anchor', 'link']:
        print('SELF-TEST FAILED: expected 2 bad anchors + 1 broken link, got %s' % checks)
        return 2
    print('self-test OK: %d findings (2 bad anchors + 1 broken link), slug rules verified' % len(checks))
    return 0


# ---------------- main ----------------

def main():
    ap = argparse.ArgumentParser(description='校验受 Git 管理的 Markdown 文档（链接/锚点/格式）')
    ap.add_argument('--self-test', action='store_true', help='运行自检（不读仓库）')
    ap.add_argument('--quiet', action='store_true', help='只输出汇总')
    ap.add_argument('-v', '--verbose', action='store_true', help='列出每个被检查的文件')
    ap.add_argument('--exclude', action='append', default=[],
                    help='额外排除的路径前缀（可重复）；默认排除 docs/feature/ 与 docs/memory/')
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    try:
        out = subprocess.run(['git', 'ls-files', '*.md'], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        print('错误: 需要 git 仓库（git ls-files 失败: %s）' % e, file=sys.stderr)
        return 2
    root = os.getcwd()
    exclusions = ('docs/feature/', 'docs/memory/') + tuple(args.exclude)
    files = [f for f in out.stdout.split() if not f.startswith(exclusions)]

    checks = []
    check_links(root, files, checks, args.verbose)
    hard_breaks = check_encoding_and_format(root, files, checks, args.verbose)

    if not args.quiet:
        for kind, rel, lineno, detail in checks:
            where = '%s:%d' % (rel, lineno) if lineno else rel
            print('%-14s %s  ->  %s' % (kind, where, detail))

    print('scanned %d markdown files: %d defect(s); trailing hard breaks kept: %d'
          % (len(files), len(checks), hard_breaks))
    return 1 if checks else 0


if __name__ == '__main__':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except AttributeError:
        pass
    sys.exit(main())
