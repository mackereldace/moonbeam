#!/usr/bin/env python3
"""Cross-check the settings layout against preferences.xml.

The layout lives in two places by necessity: the preferences and their keys stay in
app/src/main/res/xml/preferences.xml (single source of truth for defaults and text), while
the category/layer assignment lives in PREF_LAYOUT in StreamSettings.java. A key present in
the XML but missing from the table silently disappears from the UI - the classic failure of
a refactor like this, and one nobody notices for weeks.

Usage: python3 tools/check-settings-layout.py [--show]
Exit code 0 when the two sides agree, 1 otherwise.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
XML = ROOT / "app/src/main/res/xml/preferences.xml"
JAVA = ROOT / "app/src/main/java/com/limelight/preferences/StreamSettings.java"

CATEGORY_NAMES = ["Picture", "Audio", "Latency", "Input", "Network", "Debug"]
LAYER_NAMES = {"c": "common", "a": "advanced"}


def xml_keys() -> list[str]:
    text = re.sub(r"<!--.*?-->", "", XML.read_text(encoding="utf-8"), flags=re.S)
    keys = re.findall(r'android:key="([^"]+)"', text)
    return [k for k in keys if not k.startswith("category_")]


def layout_rows() -> list[tuple[str, int, str]]:
    text = JAVA.read_text(encoding="utf-8")
    block = re.search(r"PREF_LAYOUT\s*=\s*\{(.*?)\n        \};", text, re.S)
    if not block:
        raise SystemExit("PREF_LAYOUT not found in " + str(JAVA))
    # Two columns since 0.4.2: the per-category advanced layer is gone, so the
    # layer marker no longer exists.
    rows = re.findall(r'\{"([^"]+)",\s*"(\d+)"\}', block.group(1))
    return [(k, int(i), "c") for k, i in rows]


def main() -> int:
    show = "--show" in sys.argv
    keys = xml_keys()
    rows = layout_rows()

    problems: list[str] = []
    seen: dict[str, int] = {}
    for key, cat, marker in rows:
        seen[key] = seen.get(key, 0) + 1
        if cat >= len(CATEGORY_NAMES):
            problems.append(f"category index out of range: {key} -> {cat}")
        if marker not in LAYER_NAMES:
            problems.append(f"bad layer marker: {key} -> {marker}")

    for key, count in seen.items():
        if count > 1:
            problems.append(f"key appears {count} times in PREF_LAYOUT: {key}")

    missing = [k for k in keys if k not in seen]
    extra = [k for k in seen if k not in keys]

    if missing:
        problems.append("in preferences.xml but not in PREF_LAYOUT (would vanish): "
                        + ", ".join(sorted(missing)))
    if extra:
        problems.append("in PREF_LAYOUT but not in preferences.xml (renamed key?): "
                        + ", ".join(sorted(extra)))

    if show and not problems:
        for cat_index, name in enumerate(CATEGORY_NAMES):
            common = [k for k, c, m in rows if c == cat_index and m == "c"]
            advanced = [k for k, c, m in rows if c == cat_index and m == "a"]
            print(f"\n[{cat_index}] {name}  ({len(common)} common, {len(advanced)} advanced)")
            for k in common:
                print(f"    {k}")
            if advanced:
                print("    -- Advanced --")
                for k in advanced:
                    print(f"    {k}")

    if problems:
        print("FAIL")
        for p in problems:
            print("  -", p)
        return 1

    print(f"OK: {len(keys)} preferences, {len(rows)} layout rows, "
          f"{len(CATEGORY_NAMES)} categories, no key lost")
    return 0


if __name__ == "__main__":
    sys.exit(main())
