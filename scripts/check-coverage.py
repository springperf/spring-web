#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-coverage.py — 覆盖率门禁：低于阈值即失败。

用法:
    python3 scripts/check-coverage.py                 # 读聚合 CSV 并判定
    python3 scripts/check-coverage.py --csv <path>    # 指定聚合 CSV
    python3 scripts/check-coverage.py --self-test     # 自检：用合成 CSV 验证解析与判定
    python3 scripts/check-coverage.py --quiet         # 只输出失败项与汇总
    python3 scripts/check-coverage.py -v              # 输出每个模块的明细

退出码: 0 = 全部达标；1 = 存在未达标项；2 = 用法/环境错误（CSV 缺失或不可解析）

数据来源:
    `coverage-aggregate` 模块的 `jacoco:report-aggregate`（绑定在 test 阶段）产出的合并 CSV。
    它把库模块单测 + E2E 触发的覆盖合并到一份数据里，GROUP 列形如
    `coverage-aggregate/<module>`，取末段即模块名——与 scripts/coverage-report.sh 的口径一致。
    本地没有 CSV 时先跑：`bash scripts/coverage-report.sh`（或 `./mvnw test`）。

阈值（与 docs 的公开目标一致，另加汇总下限防整体退化）:
    - 汇总：行 ≥92.0%、分支 ≥83.0%（2026-10-17 实测 93.6% / 84.9%，留约 1.6~1.9pt 缓冲）
    - 模块：spring-web ≥90.0%，其余库模块 ≥80.0%

已知局限:
    - 只看行/分支两个维度；方法/指令覆盖率仅打印供参考，不设门禁。
    - 门禁依赖"聚合 CSV 存在且包含全部 7 个库模块"，缺模块按缺陷处理（避免空跑通过）。
"""

import argparse
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CSV = os.path.join(ROOT, 'coverage-aggregate', 'target', 'site',
                           'jacoco-aggregate', 'jacoco.csv')

# 与 scripts/coverage-report.sh 的 MODULES 保持一致
LIB_MODULES = (
    'spring-web',
    'spring-web-view',
    'spring-web-servlet',
    'spring-web-mvc-support',
    'spring-web-batch',
    'spring-web-websocket',
    'spring-boot-starter-web',
)

MIN_TOTAL_LINE = 92.0
MIN_TOTAL_BRANCH = 83.0
MIN_MODULE_LINE = 80.0
MIN_MODULE_LINE_OVERRIDES = {'spring-web': 90.0}

# CSV 列序：GROUP,PACKAGE,CLASS,INSTRUCTION_M,C,BRANCH_M,C,LINE_M,C,COMPLEXITY_M,C,METHOD_M,C
COL_LINE_MISSED = 7
COL_LINE_COVERED = 8
COL_BRANCH_MISSED = 5
COL_BRANCH_COVERED = 6


def parse_csv(path):
    """返回 {module: {'line': [covered, total], 'branch': [covered, total]}}。"""
    stats = {}
    with open(path, encoding='utf-8') as fh:
        header = fh.readline()
        if 'GROUP' not in header or 'LINE_COVERED' not in header:
            raise ValueError('unexpected jacoco csv header: %r' % header.strip())
        for lineno, line in enumerate(fh, 2):
            line = line.strip()
            if not line:
                continue
            cells = line.split(',')
            if len(cells) < 13:
                raise ValueError('line %d has %d columns, expected 13' % (lineno, len(cells)))
            module = cells[0].replace('\\', '/').rstrip('/').split('/')[-1]
            entry = stats.setdefault(module, {
                'line': [0, 0],
                'branch': [0, 0],
            })
            lm, lc = int(cells[COL_LINE_MISSED]), int(cells[COL_LINE_COVERED])
            bm, bc = int(cells[COL_BRANCH_MISSED]), int(cells[COL_BRANCH_COVERED])
            entry['line'][0] += lc
            entry['line'][1] += lc + lm
            entry['branch'][0] += bc
            entry['branch'][1] += bc + bm
    return stats


def pct(covered, total):
    return 100.0 * covered / total if total else 0.0


def evaluate(stats):
    """返回 (rows, failures)。rows 为 [(module, line%, branch%)]，failures 为 [(module, metric, actual, floor)]。"""
    rows, failures = [], []
    total_lc = total_lt = total_bc = total_bt = 0

    for module in LIB_MODULES:
        entry = stats.get(module)
        if entry is None:
            failures.append((module, 'missing', 0.0, 0.0))
            continue
        lc, lt = entry['line']
        bc, bt = entry['branch']
        line_pct = pct(lc, lt)
        branch_pct = pct(bc, bt)
        rows.append((module, line_pct, branch_pct))

        floor = MIN_MODULE_LINE_OVERRIDES.get(module, MIN_MODULE_LINE)
        if line_pct < floor:
            failures.append((module, 'line', line_pct, floor))

        total_lc += lc
        total_lt += lt
        total_bc += bc
        total_bt += bt

    if total_lt:
        total_line_pct = pct(total_lc, total_lt)
        total_branch_pct = pct(total_bc, total_bt)
        rows.append(('TOTAL', total_line_pct, total_branch_pct))
        if total_line_pct < MIN_TOTAL_LINE:
            failures.append(('TOTAL', 'line', total_line_pct, MIN_TOTAL_LINE))
        if total_branch_pct < MIN_TOTAL_BRANCH:
            failures.append(('TOTAL', 'branch', total_branch_pct, MIN_TOTAL_BRANCH))

    return rows, failures


def self_test():
    """用合成 CSV 验证：分组、汇总、缺失模块、阈值判定。"""
    good = ['GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,'
            'BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,'
            'COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED']
    # 数字按"全部达标"构造：各模块行覆盖 95% / 分支 90%，汇总同样高于阈值
    for module, line_c, line_m, br_c, br_m in (
            ('spring-web', 950, 50, 45, 5),
            ('spring-web-view', 95, 5, 9, 1),
            ('spring-web-servlet', 95, 5, 9, 1),
            ('spring-web-mvc-support', 95, 5, 9, 1),
            ('spring-web-batch', 95, 5, 9, 1),
            ('spring-web-websocket', 95, 5, 9, 1),
            ('spring-boot-starter-web', 95, 5, 9, 1)):
        good.append('coverage-aggregate/%s,p,C,0,0,%d,%d,%d,%d,0,0,0,0'
                    % (module, br_m, br_c, line_m, line_c))

    bad = list(good)
    bad[-1] = 'coverage-aggregate/spring-boot-starter-web,p,C,0,0,1,0,100,0,0,0,0,0'

    # 把合成行写成临时 CSV 再走 parse_csv，确保列序与真实文件一致
    for name, lines, expect_ok in (('good', good, True), ('bad', bad, False)):
        fd, path = tempfile.mkstemp(suffix='.csv')
        with os.fdopen(fd, 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(lines) + '\n')
        try:
            rows, failures = evaluate(parse_csv(path))
        finally:
            os.unlink(path)
        if expect_ok and failures:
            print('SELF-TEST FAILED (%s): unexpected failures %s' % (name, failures))
            return 2
        if not expect_ok and not failures:
            print('SELF-TEST FAILED (%s): expected a failure (threshold and/or missing module)' % name)
            return 2
        if not rows or rows[-1][0] != 'TOTAL':
            print('SELF-TEST FAILED (%s): missing TOTAL row' % name)
            return 2
    print('self-test OK: parsing, per-module floors, totals and missing-module handling')
    return 0


def main(argv):
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--csv', default=DEFAULT_CSV)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--quiet', action='store_true')
    parser.add_argument('-v', '--verbose', action='store_true')
    args, unknown = parser.parse_known_args(argv)
    if unknown:
        print('warning: ignored unknown arguments: %s' % ' '.join(unknown), file=sys.stderr)

    if args.self_test:
        return self_test()

    if not os.path.isfile(args.csv):
        print('错误: 找不到聚合覆盖率 CSV: %s' % args.csv, file=sys.stderr)
        print('      先运行 bash scripts/coverage-report.sh（或 ./mvnw test）生成。', file=sys.stderr)
        return 2

    try:
        stats = parse_csv(args.csv)
    except (OSError, ValueError) as e:
        print('错误: 无法解析覆盖率 CSV: %s' % e, file=sys.stderr)
        return 2

    rows, failures = evaluate(stats)

    if not args.quiet:
        print('module                          line%   branch%')
        for module, line_pct, branch_pct in rows:
            print('%-30s %6.1f   %6.1f' % (module, line_pct, branch_pct))

    if failures:
        print('')
        for module, metric, actual, floor in failures:
            if metric == 'missing':
                print('[FAIL] %s: 聚合 CSV 中缺少该模块（覆盖数据未生成或模块被移除）' % module)
            else:
                print('[FAIL] %s %s 覆盖率 %.1f%% < 阈值 %.1f%%' % (module, metric, actual, floor))
        return 1

    if not args.quiet:
        print('\nOK: 行/分支覆盖率全部达标（汇总行 ≥%.1f%%、分支 ≥%.1f%%）'
              % (MIN_TOTAL_LINE, MIN_TOTAL_BRANCH))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
