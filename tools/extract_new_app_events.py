#!/usr/bin/env python3
"""Keep only app-log records added since the baseline without relying on file offsets."""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path


def added_lines(before: list[str], after: list[str]) -> list[str]:
    remaining = Counter(before)
    result: list[str] = []
    for line in after:
        if remaining[line]:
            remaining[line] -= 1
        else:
            result.append(line)
    return result


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: extract_new_app_events.py BEFORE AFTER", file=sys.stderr)
        return 2
    before = Path(argv[0]).read_text(encoding="utf-8").splitlines(keepends=True)
    after = Path(argv[1]).read_text(encoding="utf-8").splitlines(keepends=True)
    sys.stdout.writelines(added_lines(before, after))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
