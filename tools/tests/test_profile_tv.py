"""Exercise CLI validation/metadata with fake ADB; no device/account data is accessed."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'profile-tv.sh'
CONFIG = dict(artworkQuality='high', navigationGlass=False, heroAnalysis=False,
              colorAnalysis=False, animations=False, progressiveArtwork=False,
              collectionMotion=False, prefetch=0)

class ProfileTvTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        adb = self.root / 'adb'
        adb.write_text('''#!/bin/bash
printf 'called\\n' >> "$FAKE_ADB_LOG"
case "$*" in
  'get-state') echo device ;;
  *'am start -W'*) printf 'Status: ok\\nLaunchState: WARM\\nTotalTime: 10\\nWaitTime: 12\\n' ;;
  *'getprop ro.product.model'*) echo Fixture ;;
  *'getprop ro.build.version.sdk'*) echo 29 ;;
esac
''')
        adb.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root)+os.pathsep+os.environ['PATH'],
                        FAKE_ADB_LOG=str(self.root/'adb.log'))
    def tearDown(self):
        self.temp.cleanup()
    def run_profile(self, profile='custom', config=None, extra=()):
        args=['bash', str(SCRIPT), str(self.root/'output'), profile, '1']
        if config is not None:
            file=self.root/'configuration.json';file.write_text(json.dumps(config))
            args += ['--custom-config', str(file)]
        return subprocess.run(args+list(extra), input='\n'*6, text=True,
                              capture_output=True, env=self.env, timeout=10)
    def test_custom_label_works_without_claiming_configuration_is_known(self):
        result=self.run_profile();self.assertEqual(0,result.returncode,result.stderr)
        info=json.loads((self.root/'output/summary.json').read_text())
        self.assertEqual('custom',info['selectedProfileLabel'])
        self.assertIsNone(info['providedCustomConfiguration'])
        self.assertFalse(info['deviceConfigurationVerified'])
    def test_custom_configuration_and_cold_option_round_trip(self):
        result=self.run_profile(config=CONFIG,extra=['--cold']);self.assertEqual(0,result.returncode,result.stderr)
        info=json.loads((self.root/'output/summary.json').read_text())
        self.assertEqual(CONFIG,info['providedCustomConfiguration'])
        self.assertTrue(info['coldRequested']);self.assertFalse(info['profileWasSetByScript'])
        self.assertEqual(10,info['medians']['TotalTimeMs'])
    def test_original_profiles_remain_supported(self):
        for profile in ('auto','low','high','fast','balanced','cinematic'):
            result=self.run_profile(profile);self.assertEqual(0,result.returncode,result.stderr)
    def test_optional_frost_method_is_validated_and_recorded(self):
        result=self.run_profile(config=dict(CONFIG,navigationGlassMethod='cached'))
        self.assertEqual(0,result.returncode,result.stderr)
        info=json.loads((self.root/'output/summary.json').read_text())
        self.assertEqual('cached',info['providedCustomConfiguration']['navigationGlassMethod'])
        result=self.run_profile(config=dict(CONFIG,navigationGlassMethod='unknown'))
        self.assertNotEqual(0,result.returncode)
    def test_bad_configuration_fails_before_adb_and_does_not_echo_private_fields(self):
        result=self.run_profile(config=dict(CONFIG, token='fixture-private-value'))
        self.assertNotEqual(0,result.returncode)
        self.assertNotIn('fixture-private-value',result.stdout+result.stderr)
        self.assertFalse((self.root/'adb.log').exists())
    def test_non_custom_profile_cannot_claim_custom_configuration(self):
        result=self.run_profile('low',config=CONFIG);self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.root/'adb.log').exists())
    def test_invalid_types_and_bounds_are_rejected(self):
        for update in ({'prefetch':True},{'prefetch':3},{'animations':'false'},{'artworkQuality':'unknown'}):
            result=self.run_profile(config=dict(CONFIG,**update));self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.root/'adb.log').exists())
    def test_unknown_or_missing_cli_options_fail_before_adb(self):
        for args in (['--unknown'],['--custom-config']):
            result=self.run_profile(extra=args);self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.root/'adb.log').exists())

if __name__ == '__main__':
    unittest.main()

class ProfileComparisonLabelsTest(ProfileTvTest):
    def test_comparison_labels_are_recorded_without_claiming_verified_configuration(self):
        result=self.run_profile('high',extra=['--device-class','older','--interface','modern','--player-interface','ember_x'])
        self.assertEqual(0,result.returncode,result.stderr)
        info=json.loads((self.root/'output/summary.json').read_text())
        self.assertEqual('older',info['deviceClassLabel']);self.assertEqual('modern',info['launcherInterfaceLabel'])
        self.assertEqual('ember_x',info['playerInterfaceLabel']);self.assertFalse(info['nonDebuggableBuildVerified'])
        self.assertFalse(info['deviceConfigurationVerified'])
    def test_invalid_comparison_labels_fail_before_adb(self):
        result=self.run_profile(extra=['--device-class','private-value'])
        self.assertNotEqual(0,result.returncode)
        self.assertNotIn('private-value',result.stdout+result.stderr)
        self.assertFalse((self.root/'adb.log').exists())
