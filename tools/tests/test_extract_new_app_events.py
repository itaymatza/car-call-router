import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "extract_new_app_events.py"
SPEC = importlib.util.spec_from_file_location("extract_new_app_events", MODULE_PATH)
extract = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = extract
SPEC.loader.exec_module(extract)


class NewAppEventsTest(unittest.TestCase):
    def test_keeps_new_records_in_order_across_rotation(self):
        before = ["old-a\n", "old-b\n", "old-b\n", "old-c\n"]
        after = ["old-b\n", "old-c\n", "new-timer\n", "new-hfp\n"]
        self.assertEqual(["new-timer\n", "new-hfp\n"], extract.added_lines(before, after))

    def test_same_text_added_again_is_not_lost(self):
        self.assertEqual(["repeat\n"], extract.added_lines(["repeat\n"], ["repeat\n", "repeat\n"]))


if __name__ == "__main__":
    unittest.main()
