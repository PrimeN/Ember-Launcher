import importlib.util
import pathlib
import sys
import unittest

ROOT=pathlib.Path(__file__).parents[1]
sys.path.insert(0,str(ROOT))
from tv_metrics import package_metadata, frame_metrics
spec=importlib.util.spec_from_file_location('profile_compare',ROOT/'profile-compare.py')
comparison=importlib.util.module_from_spec(spec);spec.loader.exec_module(comparison)

class TvMetricsTest(unittest.TestCase):
    def test_package_metadata_does_not_keep_private_dump_fields(self):
        value=package_metadata('versionName=1.2\nversionCode=123 minSdk=26\nflags=[ HAS_CODE DEBUGGABLE ]\nprivate=secret')
        self.assertTrue(value['debuggable']);self.assertEqual(123,value['versionCode'])
        self.assertNotIn('secret',str(value))
        self.assertFalse(package_metadata('flags=[ HAS_CODE ]')['debuggable'])
        self.assertIsNone(package_metadata('')['debuggable'])
    def test_frame_percentiles_ignore_invalid_and_non_drawing_records(self):
        text='Flags,IntendedVsync,FrameCompleted\n0,100000000,110000000\n0,200000000,220000000\n1,100,1000000000\n0,0,0\n'
        value=frame_metrics(text)
        self.assertEqual(2,value['frames']);self.assertEqual(15,value['p50Ms']);self.assertEqual(19.5,value['p95Ms'])
        self.assertIsNone(frame_metrics('unavailable')['p95Ms'])
    def test_comparison_does_not_treat_unknown_build_or_missing_timings_as_release_or_zero(self):
        text=comparison.compare([{},dict(package=dict(debuggable=True),medians=dict(TotalTimeMs=30))])
        self.assertIn('unknown',text);self.assertIn('debug',text);self.assertNotIn('| release |',text)
