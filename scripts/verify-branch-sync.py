#!/usr/bin/env python3
"""Verify a branch-to-branch feature sync (source -> target) from a ledger.

The checks are deliberately mechanical and reproducible:

1. commit coverage    - every source commit in scope has a ledger entry and a real target commit
2. patch identity     - entries marked "exact" really are exact (stable patch-id + same files)
3. file coverage      - no source-touched file was never touched on the target side
4. production methods - no source method disappeared (exemptions must carry a reason)
5. test methods       - no source test case disappeared (exemptions must carry a reason)
6. feature markers    - each 3.5.8 capability exists on both branches
7. branch isolation   - source-only / target-only symbols stay on their own branch
8. release metadata   - all poms and release docs carry the expected version/date

Usage:
    python3 scripts/verify-branch-sync.py \
        --ledger .agent/context/4.1.x-sync-ledger.json
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import subprocess
import sys
from pathlib import Path


def git(*args: str, input_text: str | None = None, check: bool = True) -> str:
    proc = subprocess.run(
        ["git", *args],
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


METHOD_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|sealed|non-sealed)\s+)*"
    r"[\w$<>\[\],?.]+\s+(\w+)\s*\([^;{}]*\)\s*"
    r"(?:throws\s+[\w\s,.<>?]+)?\s*\{",
    re.M,
)
COMMENT_STRING_RE = re.compile(r'/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"')
CONFLICT_RE = re.compile(r"^(?:<<<<<<<|>>>>>>>)", re.M)


class Verifier:
    def __init__(self, ledger_path: Path):
        self.ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
        self.source = self.ledger["source"]
        self.target = self.ledger["target"]
        self.base = self.ledger["base"]
        self._files: dict[str, set[str]] = {}
        self._patch: dict[str, str] = {}
        self._tree: dict[tuple[str, str], str | None] = {}
        self.results: list[tuple[bool, str, str]] = []

    def record(self, ok: bool, name: str, detail: str = "") -> None:
        self.results.append((ok, name, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""))

    # ---- git helpers -------------------------------------------------
    def scope(self, rev: str) -> list[str]:
        return git("rev-list", f"{self.base}..{rev}").splitlines()

    @staticmethod
    def subjects(rev: str, shas: list[str]) -> dict[str, str]:
        out = {}
        for line in git("log", "--format=%H%x09%s", rev).splitlines():
            h, s = line.split("\t", 1)
            out[h] = s
        return {h: out[h] for h in shas}

    def files(self, sha: str) -> set[str]:
        if sha not in self._files:
            self._files[sha] = set(git("show", "--format=", "--name-only", sha).splitlines())
        return self._files[sha]

    def patch_id(self, sha: str) -> str:
        if sha not in self._patch:
            patch = subprocess.run(
                ["git", "show", "--format=", sha],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            ).stdout
            proc = subprocess.run(
                ["git", "patch-id", "--stable"],
                input=patch,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            self._patch[sha] = proc.stdout.split()[0] if proc.stdout.split() else ""
        return self._patch[sha]

    def tree_file(self, rev: str, path: str) -> str | None:
        key = (rev, path)
        if key not in self._tree:
            proc = subprocess.run(
                ["git", "show", f"{rev}:{path}"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            self._tree[key] = proc.stdout if proc.returncode == 0 else None
        return self._tree[key]

    def grep_exists(self, rev: str, pattern: str, paths: tuple[str, ...] | None = None) -> bool:
        pathspecs = paths or ("*.java", "*.md", "*.json", "*.xml")
        proc = subprocess.run(
            ["git", "grep", "-F", "-q", pattern, rev, "--", *pathspecs],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return proc.returncode == 0

    def preload(self, *ranges: str) -> None:
        """Bulk-load patch-ids and changed file sets: one git call instead of two per commit."""
        for rng in ranges:
            wanted = set(git("rev-list", rng).splitlines())
            if not wanted:
                continue
            log = git("log", "--format=COMMIT %H", "--name-only", rng)
            current = None
            for line in log.splitlines():
                if line.startswith("COMMIT "):
                    current = line[7:].strip()
                    if current in wanted:
                        self._files.setdefault(current, set())
                elif current in wanted and line.strip():
                    self._files[current].add(line)
            patch_text = subprocess.run(
                ["git", "log", "-p", "--format=commit %H", rng],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            ).stdout
            id_text = subprocess.run(
                ["git", "patch-id", "--stable"],
                input=patch_text,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            ).stdout
            for line in id_text.splitlines():
                parts = line.split()
                if len(parts) == 2 and parts[1] in wanted:
                    self._patch[parts[1]] = parts[0]

    # ---- checks ------------------------------------------------------
    def check_commits(self, src_scope: list[str], tgt_scope: set[str]) -> None:
        ledger = self.ledger["commits"]
        expected = set(src_scope)
        missing = sorted(expected - set(ledger))
        stale = sorted(set(ledger) - expected)
        problems: list[str] = []
        counts = collections.Counter()
        for sha, entry in ledger.items():
            status = entry.get("status")
            counts[status] += 1
            target = entry.get("target")
            reason = (entry.get("reason") or "").strip()
            if status not in {"exact", "adapted", "exempt"}:
                problems.append(f"{sha[:8]}: bad status {status}")
                continue
            if status in {"adapted", "exempt"} and not reason:
                problems.append(f"{sha[:8]}: {status} without reason")
            if status == "exempt":
                continue
            if not target or target not in tgt_scope:
                problems.append(f"{sha[:8]}: target {str(target)[:8]} not in target scope")
                continue
            if status == "exact":
                if self.patch_id(sha) != self.patch_id(target):
                    problems.append(f"{sha[:8]}->{target[:8]}: patch-id differs")
                if self.files(sha) != self.files(target):
                    problems.append(f"{sha[:8]}->{target[:8]}: file set differs")
        detail = f"{len(expected)} source commits, {dict(counts)}"
        if missing:
            detail += f"; unmapped: {[s[:8] for s in missing[:6]]}"
        if stale:
            detail += f"; stale ledger entries: {[s[:8] for s in stale[:6]]}"
        self.record(not (missing or stale or problems), "commit coverage / patch identity", detail)
        for p in problems[:10]:
            print("    ! " + p)

    def check_files(self, src_scope: list[str], tgt_scope: list[str]) -> None:
        src_files: set[str] = set()
        tgt_files: set[str] = set()
        for h in src_scope:
            src_files |= self.files(h)
        for h in tgt_scope:
            tgt_files |= self.files(h)
        exempt = {e["path"] for e in self.ledger["exempt"].get("files", [])}
        missing = sorted(src_files - tgt_files - exempt)
        self.record(not missing, "file coverage", f"source={len(src_files)} target={len(tgt_files)} missing={len(missing)}")
        for p in missing[:10]:
            print("    ! " + p)

    def check_methods(self, src_scope: list[str], kind: str) -> None:
        paths = {
            p
            for h in src_scope
            for p in self.files(h)
            if p.endswith(".java") and f"/src/{kind}/" in p
        }
        exempt = {
            (e["path"], e["method"])
            for e in self.ledger["exempt"].get("prod_methods" if kind == "main" else "test_methods", [])
        }
        missing: list[tuple[str, str]] = []
        compared = 0
        for path in sorted(paths):
            src = self.tree_file(self.source, path)
            if src is None:
                continue
            tgt = self.tree_file(self.target, path)
            if tgt is None:
                continue
            compared += 1
            a = set(METHOD_RE.findall(COMMENT_STRING_RE.sub("", src)))
            b = set(METHOD_RE.findall(COMMENT_STRING_RE.sub("", tgt)))
            for name in sorted(a - b):
                if (path, name) not in exempt:
                    missing.append((path, name))
        self.record(
            not missing,
            f"{kind} method coverage",
            f"compared={compared} unexempted-missing={len(missing)}",
        )
        for path, name in missing[:10]:
            print(f"    ! {path}: {name}")

    def check_markers(self) -> None:
        markers = self.ledger["exempt"].get("markers", [])
        missing_src = [m for m in markers if not self.grep_exists(self.source, m)]
        missing_tgt = [m for m in markers if not self.grep_exists(self.target, m)]
        ok = not missing_src and not missing_tgt
        detail = f"markers={len(markers)} source-missing={len(missing_src)} target-missing={len(missing_tgt)}"
        self.record(ok, "feature markers present on both branches", detail)
        for m in (missing_src + missing_tgt)[:10]:
            print("    ! " + m)

    def check_isolation(self) -> None:
        problems: list[str] = []
        # 文档里允许解释对分支的差异，所以隔离检查只看代码与构建文件。
        code_paths = ("*.java", "pom.xml")
        if self.grep_exists(self.source, "tools.jackson", code_paths):
            problems.append("Jackson 3 leaked into source branch")
        if self.grep_exists(self.source, "spring-boot-4", code_paths):
            problems.append("Boot 4 profile leaked into source branch")
        targets = git("ls-tree", "-r", "--name-only", self.target).splitlines()
        leftovers = [p for p in targets if "ListenableFuture" in p]
        if leftovers:
            problems.append("ListenableFuture classes remain on target: " + ", ".join(leftovers[:3]))
        conflict = git("grep", "-n", "-E", r"^(<<<<<<<|>>>>>>>)", self.target, check=False)
        if conflict:
            problems.append("conflict markers in target tree")
        self.record(not problems, "branch isolation / conflict markers", "; ".join(problems))

    def check_release(self) -> None:
        version = self.ledger["expected_version"]
        date = self.ledger["expected_date"]
        problems: list[str] = []
        poms = [p for p in git("ls-tree", "-r", "--name-only", self.target).splitlines() if p.endswith("pom.xml")]
        for path in poms:
            text = self.tree_file(self.target, path) or ""
            same_series = set(re.findall(r"<version>(4\.1\.\d+)</version>", text))
            if version not in same_series or any(v != version for v in same_series):
                problems.append(f"{path}: {sorted(same_series) or 'no literal version'}")
        for doc in ("CHANGELOG.md", "docs/en/changelog.md", "docs/breaking-changes.md", "docs/en/breaking-changes.md"):
            text = self.tree_file(self.target, doc) or ""
            alt = f"{date[:4]}-{date[4:6]}-{date[6:]}"
            if version not in text or (date not in text and alt not in text):
                problems.append(f"{doc}: missing {version}/{date}")
        self.record(not problems, "release metadata", f"poms={len(poms)} expected={version}/{date}")
        for p in problems[:10]:
            print("    ! " + p)

    def run_all(self) -> int:
        src_scope = self.scope(self.source)
        tgt_scope = self.scope(self.target)
        self.preload(f"{self.base}..{self.source}", f"{self.base}..{self.target}")
        print(f"scope: {self.source} {len(src_scope)} commits -> {self.target} {len(tgt_scope)} commits")
        print(f"base:  {self.base[:10]}\n")
        self.check_commits(src_scope, set(tgt_scope))
        self.check_files(src_scope, tgt_scope)
        self.check_methods(src_scope, "main")
        self.check_methods(src_scope, "test")
        self.check_markers()
        self.check_isolation()
        self.check_release()
        failed = [name for ok, name, _ in self.results if not ok]
        print(f"\nSUMMARY: {len(self.results) - len(failed)}/{len(self.results)} checks passed")
        if failed:
            print("FAILED: " + ", ".join(failed))
            return 1
        return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ledger", required=True, type=Path, help="sync ledger JSON")
    args = ap.parse_args()
    return Verifier(args.ledger).run_all()


if __name__ == "__main__":
    sys.exit(main())
