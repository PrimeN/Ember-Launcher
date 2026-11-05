"""Summarises JUnit XML results: a table on the run's summary page and one annotation per failure.

Usage: python3 test-summary.py <results dir>. Exits 1 when no results were found, so a test
task that silently ran nothing is noticed.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


files = sorted(glob.glob(os.path.join(sys.argv[1], "**", "TEST-*.xml"), recursive=True))
total = failed = skipped = 0
seconds = 0.0
classes = 0
failures = []
for path in files:
    suite = ET.parse(path).getroot()
    classes += 1
    seconds += float(suite.get("time") or 0)
    for case in suite.iter("testcase"):
        total += 1
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            failed += 1
            name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
            message = (problem.get("message") or problem.text or "").strip().splitlines()
            failures.append((name, message[0][:300] if message else "", (problem.text or "").strip()[:2000]))
        elif case.find("skipped") is not None:
            skipped += 1

passed = total - failed - skipped
lines = [
    "## Unit tests",
    "",
    "| Result | Tests | Passed | Failed | Skipped | Classes | Time |",
    "|---|---:|---:|---:|---:|---:|---:|",
    f"| {'❌ Failed' if failed else ('⚠️ None found' if not total else '✅ Passed')} | {total} | {passed} | {failed} | {skipped} | {classes} | {seconds:.1f} s |",
]
if failures:
    lines += ["", "### Failures", ""]
    for name, first, detail in failures:
        lines += [f"<details><summary><code>{name}</code>: {first}</summary>", "", "```", detail, "```", "</details>"]
        print(f"::error title=Test failed: {name}::{escape(first or 'see test report')}")

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")
print(f"::notice title=Unit tests::{total} tests, {passed} passed, {failed} failed, {skipped} skipped in {classes} classes")
sys.exit(1 if not total else 0)
