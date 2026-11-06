#!/usr/bin/env python3
"""Check that the settings screen is actually translated.

The settings screen has three ways to end up with English on a Chinese phone:

  1. a preference references @string/foo that only exists in values/,
  2. preferences.xml carries a literal title/summary instead of a resource,
  3. a key exists in both files but the build never complained because the values file
     has it and the zh file silently falls back to English.

Only (1) and (2) are failures here; (3) is reported as a warning so the gap is visible
without pretending the whole upstream translation set is this project's job.

Usage: python3 tools/check-settings-i18n.py [--all]
Exit code 0 when the settings screen is fully translated.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
XML = ROOT / "app/src/main/res/xml/preferences.xml"
EN = ROOT / "app/src/main/res/values/strings.xml"
ZH = ROOT / "app/src/main/res/values-zh-rCN/strings.xml"


def string_keys(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    return re.findall(r'<string name="([A-Za-z0-9_]+)"', text)


def main() -> int:
    show_all = "--all" in sys.argv
    xml = XML.read_text(encoding="utf-8")
    refs = sorted(set(re.findall(r"@string/([A-Za-z0-9_]+)", xml)))

    en = string_keys(EN)
    zh = string_keys(ZH)
    en_set, zh_set = set(en), set(zh)

    problems: list[str] = []

    # Java-referenced strings on this screen (HDR report dialog, import dialogs) are just as
    # visible as the XML ones, so check those too.
    java_refs = set()
    for java in [ROOT / "app/src/main/java/com/limelight/preferences/StreamSettings.java",
                 ROOT / "app/src/main/java/com/limelight/utils/HdrDisplayCapabilities.java"]:
        java_refs |= set(# (?!android\.) so android.R.string.ok/cancel do not look like app strings
        re.findall(r"(?<!android\.)R\.string\.([A-Za-z0-9_]+)", java.read_text(encoding="utf-8")))
    java_refs -= {"category_audio_settings", "category_input_settings"}
    refs = sorted(set(refs) | java_refs)

    missing_en = [k for k in refs if k not in en_set]
    missing_zh = [k for k in refs if k not in zh_set]
    if missing_en:
        problems.append("missing from values/strings.xml: " + ", ".join(missing_en))
    if missing_zh:
        problems.append("missing from values-zh-rCN/strings.xml: " + ", ".join(missing_zh))

    literals = [
        f"line {i}: {line.strip()}"
        for i, line in enumerate(xml.splitlines(), 1)
        if re.search(r'android:(title|summary|dialogMessage|text)="[^@]', line)
    ]
    if literals:
        problems.append("literal UI text in preferences.xml (must be @string):\n    "
                        + "\n    ".join(literals))

    duplicates = sorted({k for k in en if en.count(k) > 1})
    if duplicates:
        problems.append("duplicate in values/strings.xml: " + ", ".join(duplicates))
    duplicates_zh = sorted({k for k in zh if zh.count(k) > 1})
    if duplicates_zh:
        problems.append("duplicate in values-zh-rCN/strings.xml: " + ", ".join(duplicates_zh))

    only_en = sorted(en_set - zh_set)
    if only_en:
        shown = only_en if show_all else only_en[:10]
        print(f"warning: {len(only_en)} strings exist in English but not in zh-rCN"
              f"{'' if show_all else ' (first 10, pass --all for the rest)'}")
        for key in shown:
            print("   ", key)
        print()

    if problems:
        print("FAIL")
        for p in problems:
            print("  -", p)
        return 1

    print(f"OK: {len(refs)} strings referenced by the settings screen exist in both locales, "
          f"no literal UI text")
    return 0


if __name__ == "__main__":
    sys.exit(main())
