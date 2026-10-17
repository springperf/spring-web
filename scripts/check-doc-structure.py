#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-doc-structure.py — 校验受 Git 管理的 Markdown 的**结构完整性**。

用法:
    python3 scripts/check-doc-structure.py              # 门禁：S1~S5 全部拦截
    python3 scripts/check-doc-structure.py --self-test  # 自检：用合成文档验证每条规则
    python3 scripts/check-doc-structure.py --quiet      # 只输出汇总
    python3 scripts/check-doc-structure.py -v           # 列出每个被检查的文件

退出码: 0 = 通过；1 = 存在缺陷；2 = 用法/环境错误

与 check-docs.py / check-doc-symbols.py 的分工（三者不重叠）:
    check-docs.py           坏链、坏锚点、中英配对、BOM/编码/尾换行/H1/行尾空格；
    check-doc-symbols.py    文档点名的配置键/类名/成员在源码中存在；
    本脚本                  只查 markdown 的**结构**，即"编辑过程的残留"。

规则:
    S1 中文括号残留：`（` 后紧跟空白、`）` 前有空白、`（` 后直接是 `，`/`；`、
       `，`/`；` 后直接是 `）`、空括号 `（）`。这些几乎都是删改内容时留下的痕迹
       （实测 docs/internals 第二轮修订前有 40 余处）。
    S2 空行内代码：连续两个反引号且内部无内容（行内代码被删空的残留）。
       **不**误报围栏 ``` 与四反引号块：负向断言保证前后都不是反引号。
    S3 表格结构：同一表格块内各行列数必须一致；不允许"整列为空"的列
       （后者是"删行号/删证据"后最常见的残留，空列会渲染出一条无意义的竖线）。
       转义的 `\\|` 不参与分列。
    S4 代码围栏配对：每个文件 ``` 标记数为偶数，否则围栏吞掉后文。
    S5 `.java` 引用存在性：形如 `Xxx.java` 的行内引用必须是仓库内受 Git 管理的文件。
       它只回答"文件在不在"，不回答"这段话对不对"（句子真假仍需人工回代码）。
       `.agent/` 下除 `module.md` 外的文件不参与 S5（与 check-doc-symbols.py 的口径一致：
       那里是历史材料与 backport 清单，职责就是记录"别的分支上有、master 上没有"的文件）。

已知局限:
    - 只处理 UTF-8 文本；非 UTF-8 文件由 check-docs.py 报，本脚本跳过。
    - 表格列数检查不解析 HTML `<table>`，只认 `|` 表格。
    - S1 的空白判断不区分中英文混排的刻意留白（如 `（ JDK 17 ）`），一律报出——
      文档既有风格是中文括号内不留空格，统一后更稳定。
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# S1: 中文括号/标点周围的空白与残句
S1_PATTERNS = (
    ('empty-paren', re.compile(r'（[ \t]*）')),
    ('paren-open-space', re.compile(r'（[ \t]+')),
    ('paren-close-space', re.compile(r'[ \t]+）')),
    ('paren-comma', re.compile(r'（[ \t]*[，；]')),
    ('comma-close', re.compile(r'[，；][ \t]*）')),
)
# S2: 空行内代码（负向断言避开围栏）
S2_PATTERN = re.compile(r'(?<!`)``(?!`)')
# S5: 行内引用的 java 文件名
S5_PATTERN = re.compile(r'`([A-Za-z][A-Za-z0-9_]*\.java)`')
# 行内代码段：S1 只查反引号**外**的正文——文档可以引用 `（，` 这类反例来讲解规则
INLINE_CODE = re.compile(r'`[^`]*`')


def git_ls_files(pattern):
    try:
        out = subprocess.run(['git', 'ls-files', pattern], cwd=ROOT,
                             capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        print('错误: 无法执行 git ls-files（%s）' % e, file=sys.stderr)
        sys.exit(2)
    return [p for p in out.stdout.decode('utf-8', errors='replace').split('\n') if p]


def iter_lines(text):
    """产出 (lineno, line, in_fence)，围栏行本身 in_fence=True 且不参与规则检查。"""
    in_fence = False
    for no, line in enumerate(text.split('\n'), 1):
        if line.lstrip().startswith('```'):
            in_fence = not in_fence
            yield no, line, True
            continue
        yield no, line, in_fence


def split_row(line):
    """按未转义的 `|` 分列，去掉首尾竖线。"""
    body = line.strip()
    if body.startswith('|'):
        body = body[1:]
    if body.endswith('|') and not body.endswith('\\|'):
        body = body[:-1]
    return [c.replace('\x00', '\\|').strip()
            for c in body.replace('\\|', '\x00').split('|')]


def is_separator(cells):
    return bool(cells) and all(re.fullmatch(r':?-{2,}:?', c) for c in cells if c)


def s5_applies(rel):
    """S5 只对"宣称当前事实"的文档生效；历史材料与 backport 清单豁免。"""
    if rel.startswith('.agent/') and os.path.basename(rel) != 'module.md':
        return False
    return True


def check_file(rel, text, java_names, checks):
    lines = list(iter_lines(text))
    fences = sum(1 for _, line, _ in lines if line.lstrip().startswith('```'))
    if fences % 2:
        checks.append(('fence', rel, 0, 'unbalanced code fences (%d)' % fences))

    table = []          # (lineno, cells)
    for no, line, in_fence in lines:
        if in_fence:
            continue
        # 用非空白占位符替换行内代码：换成空格会把合法的 `（`code`）` 误判成空括号
        prose = INLINE_CODE.sub('\x01', line)
        for kind, rx in S1_PATTERNS:
            m = rx.search(prose)
            if m:
                checks.append((kind, rel, no, m.group(0).strip() or '（）'))
        if S2_PATTERN.search(line):
            checks.append(('empty-code-span', rel, no, '``'))
        if s5_applies(rel):
            for m in S5_PATTERN.finditer(line):
                if m.group(1) not in java_names:
                    checks.append(('missing-java-ref', rel, no, m.group(1)))

        if line.startswith('|'):
            table.append((no, split_row(line)))
            continue
        if table:
            check_table(rel, table, checks)
            table = []
    if table:
        check_table(rel, table, checks)


def check_table(rel, table, checks):
    header_no, header = table[0]
    body = [(no, cells) for no, cells in table[1:] if not is_separator(cells)]
    for no, cells in body:
        if len(cells) != len(header):
            checks.append(('table-width', rel, no,
                           '%d columns, header has %d' % (len(cells), len(header))))
    # 整列为空：只在列数正确的行上判断（短行已单独报 table-width，不再参与空列统计）
    aligned = [(no, cells) for no, cells in body if len(cells) == len(header)]
    for idx, title in enumerate(header):
        if aligned and all(cells[idx] == '' for _, cells in aligned):
            checks.append(('table-empty-column', rel, header_no,
                           'column %d "%s" is empty for all %d rows' % (idx + 1, title, len(aligned))))


def self_test():
    java_names = {'Exists.java'}
    good = ('# T\n\n'
            '| a | b |\n|---|---|\n| 1 | 2 |\n\n'
            '见 `Exists.java`，无残留。\n\n'
            '```java\nString s = "（ ，）";\n```\n')
    bad = ('# T\n\n'
           '- 构建 `X`（，适配 `Y`）。\n'
           '- 取 `Z`（ `volatile int` ）。\n'
           '- 残留 `` 空代码。\n\n'
           '| a | b | c |\n|---|---|---|\n| 1 | 2 |\n| 3 | 4 |  |\n\n'
           '见 `Missing.java`。\n\n'
           '```java\nint x = 1;\n')
    good_checks, bad_checks = [], []
    check_file('good.md', good, java_names, good_checks)
    check_file('bad.md', bad, java_names, bad_checks)
    kinds = sorted(c[0] for c in bad_checks)
    expected = sorted([
        'paren-comma', 'paren-open-space', 'paren-close-space',
        'empty-code-span', 'table-width', 'table-empty-column',
        'missing-java-ref', 'fence',
    ])
    if good_checks:
        print('SELF-TEST FAILED: good doc produced %s' % good_checks)
        return 2
    missing = [k for k in expected if k not in kinds]
    if missing:
        print('SELF-TEST FAILED: missing %s (got %s)' % (missing, kinds))
        return 2
    print('self-test OK: %d findings across %d rules; clean doc stays clean'
          % (len(bad_checks), len(expected)))
    return 0


def main(argv):
    if '--self-test' in argv:
        return self_test()

    quiet = '--quiet' in argv
    verbose = '-v' in argv or '--verbose' in argv

    docs = git_ls_files('*.md')
    java_names = {os.path.basename(p) for p in git_ls_files('*.java')}
    if not docs:
        print('错误: 未找到受 Git 管理的 Markdown（请在仓库根目录执行）', file=sys.stderr)
        return 2

    checks = []
    for rel in docs:
        full = os.path.join(ROOT, rel)
        try:
            with open(full, encoding='utf-8', errors='replace') as fh:
                text = fh.read().replace('\r\n', '\n').replace('\r', '\n')
        except OSError as e:
            checks.append(('read', rel, 0, str(e)))
            continue
        if verbose:
            print('  checked: %s' % rel)
        check_file(rel, text, java_names, checks)

    if checks:
        for kind, rel, no, detail in checks:
            loc = '%s:%d' % (rel, no) if no else rel
            print('%s [%s] %s' % (loc, kind, detail))
        print('\n%d defect(s) across %d markdown file(s)' % (len(checks), len(docs)))
        return 1
    if not quiet:
        print('OK: %d markdown file(s) structurally clean (S1~S5)' % len(docs))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
