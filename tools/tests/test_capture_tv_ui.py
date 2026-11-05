import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).parents[1]
PNG=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ7sAAAAASUVORK5CYII=')

class CaptureUiTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        (self.root/'screen.png').write_bytes(PNG)
        adb=self.root/'adb'
        adb.write_text('#!/bin/bash\nif [[ "$*" == "get-state" ]]; then echo device; else cat "$FIXTURE_SCREEN"; fi\n')
        adb.chmod(0o755)
        self.env=dict(os.environ,PATH=str(self.root)+os.pathsep+os.environ['PATH'],FIXTURE_SCREEN=str(self.root/'screen.png'))
    def tearDown(self):self.tmp.cleanup()
    def capture(self,case='modern-player'):
        return subprocess.run(['bash',str(ROOT/'capture-tv-ui.sh'),str(self.root/'output'),case],env=self.env,capture_output=True,text=True,timeout=10)
    def test_local_png_is_saved_and_is_not_marked_visually_approved(self):
        result=self.capture();self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(PNG,(self.root/'output/modern-player.png').read_bytes())
        self.assertIn('pending reviewer inspection',result.stdout)
    def test_invalid_or_truncated_png_is_not_kept(self):
        for data in (b'error',PNG[:20]):
            (self.root/'screen.png').write_bytes(data)
            self.assertNotEqual(0,self.capture().returncode)
            self.assertFalse((self.root/'output/modern-player.png').exists())
    def test_case_id_cannot_escape_the_output_directory(self):
        self.assertNotEqual(0,self.capture('../outside').returncode)
        self.assertFalse((self.root/'outside.png').exists())
