#!/usr/bin/env python3
"""Render deterministic JaCoCo XML totals for CI job summaries."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Coverage:
    covered: int
    missed: int

    @property
    def ratio(self) -> float:
        total = self.covered + self.missed
        return self.covered / total if total else 1.0


def read_counters(path: Path) -> dict[str, Coverage]:
    root = ET.parse(path).getroot()
    counters = {
        item.attrib["type"]: Coverage(
            covered=int(item.attrib["covered"]),
            missed=int(item.attrib["missed"]),
        )
        for item in root.findall("counter")
    }
    missing = {"LINE", "BRANCH"} - counters.keys()
    if missing:
        raise ValueError(f"{path}: missing JaCoCo counters: {', '.join(sorted(missing))}")
    return counters


def markdown(reports: list[tuple[str, Path]]) -> str:
    rows = ["## Test coverage", "", "| Module | Line | Branch |", "|---|---:|---:|"]
    for name, path in reports:
        counters = read_counters(path)
        rows.append(
            f"| {name} | {counters['LINE'].ratio:.1%} | {counters['BRANCH'].ratio:.1%} |",
        )
    return "\n".join(rows) + "\n"


def parse_report(value: str) -> tuple[str, Path]:
    name, separator, raw_path = value.partition("=")
    if not separator or not name or not raw_path:
        raise argparse.ArgumentTypeError("reports must use NAME=PATH")
    return name, Path(raw_path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", action="append", required=True, type=parse_report)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        summary = markdown(args.report)
    except (OSError, ET.ParseError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(summary, end="")
    if args.output:
        with args.output.open("a", encoding="utf-8") as stream:
            stream.write(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
