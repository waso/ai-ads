#!/usr/bin/env python3
"""Checks every acceptance criterion in ACCEPTANCE.md against the Surefire reports.

A criterion is MET only when at least one test whose display name carries its [AC-nn] tag ran and every such test
passed. A criterion with no test, a skipped test or a failing test is NOT met. Exits non-zero unless all are met.

Run `mvn verify` (or `mvn test`) first; this script only reads target/surefire-reports.
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TAG = re.compile(r"\[(AC-\d+)\]")


def criteria():
    found = {}
    for line in (ROOT / "ACCEPTANCE.md").read_text().splitlines():
        m = re.match(r"\|\s*(AC-\d+)\s*\|\s*(.+?)\s*\|\s*$", line)
        if m:
            found[m.group(1)] = m.group(2)
    return found


def results():
    """AC id -> list of (test name, status) where status is pass, fail or skip."""
    out = defaultdict(list)
    reports = sorted(glob.glob(str(ROOT / "target" / "surefire-reports" / "TEST-*.xml")))
    for path in reports:
        for case in ET.parse(path).getroot().iter("testcase"):
            name = case.get("name", "")
            tags = set(TAG.findall(name))
            if not tags:
                continue
            if case.find("failure") is not None or case.find("error") is not None:
                status = "fail"
            elif case.find("skipped") is not None:
                status = "skip"
            else:
                status = "pass"
            for tag in tags:
                out[tag].append((name, status))
    return out, len(reports)


def build_is_clean():
    """AC-38 is a property of the whole build, so it has no tag: every report is clean and the jar was packaged."""
    reports = sorted(glob.glob(str(ROOT / "target" / "surefire-reports" / "TEST-*.xml")))
    if not reports:
        return False, "no Surefire reports"
    bad = 0
    total = 0
    for path in reports:
        root = ET.parse(path).getroot()
        total += int(root.get("tests", 0))
        bad += int(root.get("failures", 0)) + int(root.get("errors", 0))
    if bad:
        return False, f"{bad} failing or erroring test(s)"
    if not glob.glob(str(ROOT / "target" / "ads-autopilot-*.jar")):
        return False, "no packaged jar: run `mvn clean verify`, not just `mvn test`"
    return True, f"{total} tests, none failing, jar packaged"


def main():
    wanted = criteria()
    got, report_count = results()
    clean, clean_note = build_is_clean()
    if "AC-38" in wanted:
        got["AC-38"] = [(f"build: {clean_note}", "pass" if clean else "fail")]
    if report_count == 0:
        print("No Surefire reports found. Run `mvn verify` first.")
        return 2

    width = max(len(text) for text in wanted.values()) if wanted else 0
    not_met = []
    print(f"{'ID':<7} {'Status':<8} {'Tests':>5}  Criterion")
    print("-" * 100)
    for ac in sorted(wanted, key=lambda a: int(a.split("-")[1])):
        tests = got.get(ac, [])
        passed = sum(1 for _, s in tests if s == "pass")
        failed = [n for n, s in tests if s == "fail"]
        skipped = [n for n, s in tests if s == "skip"]
        if failed or skipped or passed == 0:
            status = "FAIL" if failed else ("SKIPPED" if skipped else "NO TEST")
            not_met.append((ac, status, failed, skipped))
        else:
            status = "met"
        text = wanted[ac]
        print(f"{ac:<7} {status:<8} {passed:>5}  {text[:78]}{'...' if len(text) > 78 else ''}")

    unknown = sorted(set(got) - set(wanted))
    if unknown:
        print(f"\nTests tagged with criteria that are not in ACCEPTANCE.md: {', '.join(unknown)}")
    print("-" * 100)
    met = len(wanted) - len(not_met)
    print(f"{met} of {len(wanted)} criteria met.")
    for ac, status, failed, skipped in not_met:
        print(f"  {ac}: {status}")
        for n in failed:
            print(f"      failing: {n}")
        for n in skipped:
            print(f"      skipped: {n}")
    if unknown or not_met:
        return 1
    print("All acceptance criteria are met. Deploy-time checks (see ACCEPTANCE.md) are not covered by the build.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
