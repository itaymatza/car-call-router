import tempfile
import unittest
from pathlib import Path

from tools.summarize_coverage import Coverage, main, markdown, read_counters


REPORT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<report name="test">
  <counter type="LINE" missed="20" covered="80"/>
  <counter type="BRANCH" missed="3" covered="7"/>
</report>
"""


class CoverageSummaryTest(unittest.TestCase):
    def test_reads_report_totals_and_renders_markdown(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text(REPORT, encoding="utf-8")
            counters = read_counters(report)
            self.assertEqual(Coverage(80, 20), counters["LINE"])
            self.assertEqual(0.7, counters["BRANCH"].ratio)
            rendered = markdown([("routing core", report)])
            self.assertIn("| routing core | 80.0% | 70.0% |", rendered)

    def test_missing_required_counter_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text(
                '<report name="test"><counter type="LINE" missed="0" covered="1"/></report>',
                encoding="utf-8",
            )
            self.assertEqual(1, main(["--report", f"core={report}"]))

    def test_zero_coverable_items_are_complete(self):
        self.assertEqual(1.0, Coverage(0, 0).ratio)
