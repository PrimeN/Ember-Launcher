"""Bounded parsers for non-private ADB performance output; missing values stay unknown."""
import csv
import json
import re
import statistics
import sys


def package_metadata(text):
    version = re.search(r'\bversionName=([^\s]+)', text)
    code = re.search(r'\bversionCode=(\d+)', text)
    flags = re.search(r'(?:pkgFlags|flags)=\[([^]]*)\]', text)
    return {'versionName': version[1] if version else None,
            'versionCode': int(code[1]) if code else None,
            'debuggable': ('DEBUGGABLE' in flags[1].split()) if flags else None}


def frame_metrics(text):
    values = []
    columns = None
    for row in csv.reader(text.splitlines()[:250_000]):
        if 'IntendedVsync' in row and 'FrameCompleted' in row:
            columns = {name.strip(): i for i, name in enumerate(row)}
            continue
        if not columns:
            continue
        try:
            if int(row[columns['Flags']]) != 0:
                continue
            start = int(row[columns['IntendedVsync']])
            end = int(row[columns['FrameCompleted']])
            if start > 0 and end > start:
                values.append((end - start) / 1_000_000)
        except (IndexError, ValueError, KeyError):
            continue
    values.sort()
    def percentile(fraction):
        if not values:
            return None
        index = (len(values) - 1) * fraction
        low = int(index)
        high = min(low + 1, len(values) - 1)
        return round(values[low] + (values[high] - values[low]) * (index - low), 3)
    jank = re.search(r'Janky frames:\s*\d+\s*\(([\d.]+)%\)', text)
    return {'frames': len(values), 'p50Ms': percentile(.50), 'p95Ms': percentile(.95),
            'p99Ms': percentile(.99), 'systemJankPercent': float(jank[1]) if jank else None}


if __name__ == '__main__':
    if sys.argv[1:] != ['--package']:
        raise SystemExit('Usage: tv_metrics.py --package < package-metadata')
    # Never persist the raw package dump.
    print(json.dumps(package_metadata(sys.stdin.read(2_000_000))))
