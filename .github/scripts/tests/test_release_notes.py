import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_notes', Path(__file__).parents[1] / 'release-notes.py')
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'app').mkdir()
        self.source = self.root / 'app/src/main/java/app/ember/tv/data/About.kt'
        self.source.parent.mkdir(parents=True)
        (self.root / 'app/build.gradle.kts').write_text('versionName = "0.9.5"\n')
        self.changes = ['A title with "quotes", parentheses (and commas), and a new line\nhere.', 'Écran and subtitles']
        self.write_entries()

    def write_entries(self, version='0.9.5', changes=None):
        changes = self.changes if changes is None else changes
        items = ',\n'.join(json.dumps(s) for s in changes)
        self.source.write_text('''// Release("0.9.5", "Comment only", listOf("not a release"))
val CHANGELOG = listOf(
Release("''' + version + '''", "Home", listOf(''' + items + ''')),
Release("0.9", "Older", listOf("Older features")),
)''', encoding='utf-8')

    def render(self, signed=True, ref='refs/heads/main'):
        return notes.render(self.root, ref, 42, 'a' * 40, signed)

    def test_only_matching_version_is_published_and_escapes_are_decoded(self):
        metadata, body = self.render()
        self.assertEqual('v0.9.5', metadata['tag'])
        for change in self.changes:
            self.assertIn(change, body)
        self.assertNotIn('Older features', body)
        self.assertNotIn('not a release', body)
        self.assertIn('versionCode: 42', body)

    def test_missing_version_cannot_publish_unrelated_notes(self):
        self.write_entries(version='0.9.4')
        with self.assertRaises(ValueError):
            self.render()

    def test_empty_changes_cannot_publish_an_empty_release(self):
        self.write_entries(changes=[])
        with self.assertRaises(ValueError):
            self.render()

    def test_wrong_tag_cannot_mislabel_an_apk(self):
        with self.assertRaises(ValueError):
            self.render(ref='refs/tags/v0.9.6')
        self.assertEqual('v0.9.5', self.render(ref='refs/tags/v0.9.5')[0]['tag'])

    def test_duplicate_version_entries_are_rejected(self):
        self.source.write_text(self.source.read_text() + '\nRelease("0.9.5", "Duplicate", listOf("Duplicate"))')
        with self.assertRaises(ValueError):
            self.render()

    def test_debug_and_release_signing_are_explained_accurately(self):
        self.assertIn('**Testing APK:**', self.render(signed=False)[1])
        self.assertIn('different key', self.render(signed=False)[1])
        self.assertNotIn('**Testing APK:**', self.render(signed=True)[1])
        self.assertIn('configured Ember signing key', self.render(signed=True)[1])

    def test_invalid_build_metadata_is_rejected(self):
        for build, commit in [(0, 'a' * 40), (2100000001, 'a' * 40), (42, 'not-a-commit')]:
            with self.assertRaises(ValueError):
                notes.render(self.root, 'refs/heads/main', build, commit, True)

    def test_four_part_patch_versions_match_their_tag_and_changelog(self):
        (self.root / 'app/build.gradle.kts').write_text('versionName = "0.9.5.1"\n')
        self.write_entries(version='0.9.5.1')
        metadata, body = self.render(ref='refs/tags/v0.9.5.1')
        self.assertEqual('v0.9.5.1', metadata['tag'])
        self.assertIn('## 0.9.5.1', body)
        with self.assertRaises(ValueError):
            self.render(ref='refs/tags/v0.9.5')


if __name__ == '__main__':
    unittest.main()
