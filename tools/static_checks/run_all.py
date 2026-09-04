"""Run every static check and summarise.

    python tools/static_checks/run_all.py

Exits non-zero if any check reports a finding, so it can be wired into CI once a
build exists. Run from the repository root.
"""
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent

CHECKS = [
    ("channel argument keys", "check_channel_keys.py"),
    ("kotlin named arguments", "check_kotlin_named_args.py"),
    ("dart widget arguments", "check_widget_args.py"),
    ("unterminated strings", "check_strings.py"),
]


def main():
    if not (ROOT / "pubspec.yaml").exists():
        print("run this from the repository root", file=sys.stderr)
        return 2

    failures = []
    for title, script in CHECKS:
        print("=" * 62)
        print(title)
        print("=" * 62)
        result = subprocess.run(
            [sys.executable, str(HERE / script)], cwd=str(ROOT)
        )
        if result.returncode:
            failures.append(title)
        print()

    print("=" * 62)
    if failures:
        print("checks with findings: %s" % ", ".join(failures))
        print("see tools/static_checks/README.md for the one known benign finding")
    else:
        print("all checks clean")
    print("=" * 62)
    print()
    print("Reminder: these do not replace `flutter analyze` or")
    print("`./gradlew :app:testDebugUnitTest`. They cannot verify types.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
