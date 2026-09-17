#!/usr/bin/env python3
"""Find app-owned native UI and Compose interoperability wrappers left in production Kotlin."""
import argparse
from pathlib import Path
import re

NATIVE_IMPORT = re.compile(r'^\s*import\s+(?:android\.widget\.(?!Toast\b)(?:\w+|\*)|android\.app\.(?:AlertDialog|Dialog)\b|androidx\.appcompat\.(?:widget|app\.AlertDialog)\b|com\.google\.android\.material\.(?:button|card|textfield)\b)', re.M)
INTEROP = re.compile(r'\b(?:AndroidView|AndroidViewBinding)\s*(?:\(|\{)')
NATIVE_CONSTRUCTOR = re.compile(r'\b(?:android\.widget\.)?(?:View|ViewGroup|LinearLayout|FrameLayout|ScrollView|TextView|EditText|ImageView|PopupWindow|RadioGroup|CheckBox|RadioButton|MaterialButton|MaterialCardView)\s*\(')
QUALIFIED_NATIVE = re.compile(r'\bandroid\.(?:widget\.(?!Toast\b)\w+|app\.(?:AlertDialog|Dialog)\b)')
INTEROP_IMPORT = re.compile(r'^\s*import\s+androidx\.compose\.(?:ui\.viewinterop\.AndroidView|ui\.viewinterop\.\*|ui\.viewbinding\.AndroidViewBinding)', re.M)
ROOT_VIEW = re.compile(r':\s*(?:android\.view\.)?(?:View|ViewGroup)\s*\(')


def findings(root):
    result = []
    for path in sorted(root.rglob('*.kt')):
        # Strip block and full-line comments; source strings are retained so fixture-like
        # native construction cannot disguise a legacy screen under another filename.
        text = re.sub(r'/\*.*?\*/', lambda match: '\n' * match.group().count('\n'), path.read_text(), flags=re.S)
        compose_names = set(re.findall(r'^\s*import\s+androidx\.compose\.(?:material3|material)\.(\w+)\s*$', text, re.M))
        for index, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith('//'):
                continue
            constructor = NATIVE_CONSTRUCTOR.search(line)
            native_constructor = constructor and constructor.group().split('(')[0].strip() not in compose_names
            if native_constructor or any(pattern.search(line) for pattern in (NATIVE_IMPORT, INTEROP, ROOT_VIEW, QUALIFIED_NATIVE, INTEROP_IMPORT)):
                result.append((str(path), index, line.strip()))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1]/'android/app/src/main/java')
    parser.add_argument('--report', action='store_true', help='Inventory only; do not fail while a migration is in progress')
    args = parser.parse_args()
    result = findings(args.root)
    for path, line, source in result:
        print(f'{path}:{line}: {source}')
    print(f'{len(result)} native UI/interoperability references across {len({x[0] for x in result})} files')
    return 0 if args.report or not result else 1


if __name__ == '__main__':
    raise SystemExit(main())
