"""Render public release notes from the matching in-app changelog entry."""
import argparse
import json
import os
from pathlib import Path
import re

STRING = r'"(?:[^"\\]|\\.)*"'
ENTRY = re.compile(
    rf'\bRelease\(\s*({STRING})\s*,\s*({STRING})\s*,\s*listOf\(\s*'
    rf'((?:{STRING}\s*,\s*)*(?:{STRING})\s*,?\s*)\)\s*,?\s*\)', re.S,
)


def current_release(root):
    build = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
    found = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', build, re.M)
    if len(found) != 1 or not re.fullmatch(r'\d+\.\d+\.\d+(?:\.\d+)?(?:[-+][\w.-]+)?', found[0]):
        raise ValueError('Expected one three- or four-part versionName in app/build.gradle.kts')
    version = found[0]
    source = (root / 'app/src/main/java/app/ember/tv/data/About.kt').read_text(encoding='utf-8')
    # Remove comments without interpreting comment markers inside string literals.
    source = re.sub(rf'{STRING}|/\*.*?\*/|//[^\n]*',
                    lambda m: m[0] if m[0].startswith('"') else ' ', source, flags=re.S)
    entries = []
    for match in ENTRY.finditer(source):
        entry_version, title = (json.loads(match[i]) for i in (1, 2))
        changes = [json.loads(s) for s in re.findall(STRING, match[3])]
        if entry_version == version:
            entries.append((title, changes))
    if len(entries) != 1 or not entries[0][1]:
        raise ValueError(f'Expected one nonempty in-app changelog entry for {version}')
    title, changes = entries[0]
    if '\n' in title or '\r' in title:
        raise ValueError('Release titles must use a single line')
    return version, title, changes


def render(root, ref, version_code, commit, signed):
    version, title, changes = current_release(root)
    tag = f'v{version}'
    if ref.startswith('refs/tags/') and ref != f'refs/tags/{tag}':
        raise ValueError(f'Tag {ref} does not match app version {version}')
    if not 1 <= version_code <= 2100000000 or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected a positive Android version code and full commit SHA')
    notes = [f'## {version} — {title}', '', *[f'- {c}' for c in changes], '', '### APK', '']
    if signed:
        notes += ['Release APK signed with the configured Ember signing key.']
    else:
        notes += ['**Testing APK:** release signing is not configured, so this APK uses a debug signing key. '
                  'It may require uninstalling an existing installation signed with a different key. '
                  'Configure a persistent release keystore for normal in-place updates.']
    notes += ['', f'versionCode: {version_code}', f'Built from `{commit}`.', '']
    return dict(version=version, tag=tag, name=f'Ember TV {version} — {title}'), '\n'.join(notes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--ref', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--signed', choices=('true', 'false'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        metadata, notes = render(args.root, args.ref, args.version_code, args.commit, args.signed == 'true')
    except (ValueError, OSError) as error:
        parser.error(str(error))
    args.output.write_text(notes, encoding='utf-8')
    metadata['notes_file'] = str(args.output.resolve())
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            for key, value in metadata.items():
                output.write(f'{key}={value}\n')
    print(f'Prepared release notes for {metadata["tag"]}')


if __name__ == '__main__':
    main()
