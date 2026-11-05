import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("design_matrix", pathlib.Path(__file__).parents[1] / "design-matrix.py")
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

class DesignMatrixTest(unittest.TestCase):
    def test_every_supported_combination_is_unique_and_unverified(self):
        rows = list(matrix.cases())
        self.assertEqual(2268, len(rows))
        self.assertEqual(len(rows), len(set(rows)))
        self.assertTrue(all(row[-1] == "PENDING" for row in rows))

    def test_each_accent_gets_day_night_and_oled(self):
        for accent in matrix.ACCENTS:
            self.assertEqual({"Day", "Night", "OLED"}, {r[1] for r in matrix.cases() if r[0] == accent})

    def test_all_player_layouts_are_checked_with_each_launcher(self):
        self.assertEqual(9, len({(r[2], r[3]) for r in matrix.cases()}))
        self.assertEqual(set(matrix.PLAYERS), {r[3] for r in matrix.cases()})
