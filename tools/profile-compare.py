#!/usr/bin/env python3
"""Compare captured summaries without accessing a device, credentials or app data."""
import json
import pathlib
import sys


def load(path):
    file = pathlib.Path(path)
    if file.stat().st_size > 1_000_000:
        raise ValueError('Summary exceeds size limit.')
    value = json.loads(file.read_text())
    if not isinstance(value, dict):
        raise ValueError('Invalid summary.')
    return value


def cell(value):
    return 'unknown' if value is None else str(value).replace('|', '/').replace('\n', ' ')[:100]


def compare(summaries):
    rows = ['| Device / profile | Build | Startup median ms | Home p95 ms | Search p95 ms | Guide p95 ms |',
            '| --- | --- | ---: | ---: | ---: | ---: |']
    for value in summaries:
        label = f"{value.get('deviceClassLabel', 'unspecified')} / {value.get('selectedProfileLabel', 'unknown')}"
        debug = value.get('package', {}).get('debuggable')
        build = 'debug' if debug is True else 'non-debuggable' if debug is False else 'unknown'
        fields = [label, build, value.get('medians', {}).get('TotalTimeMs')]
        fields += [value.get('frames', {}).get(page, {}).get('p95Ms') for page in ('home', 'search', 'guide')]
        rows.append('| ' + ' | '.join(cell(x) for x in fields) + ' |')
    rows += ['', 'Compare matching content, refresh rate, launch state and settings. Device/profile labels are supplied by the operator; they are not verified configuration.']
    return '\n'.join(rows)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit('Usage: profile-compare.py SUMMARY_A.json SUMMARY_B.json [...]')
    try:
        print(compare([load(p) for p in sys.argv[1:]]))
    except (OSError, ValueError, TypeError, KeyError):
        raise SystemExit('Could not read valid bounded profiling summaries.')
