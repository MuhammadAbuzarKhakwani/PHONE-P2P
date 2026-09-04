"""Cross-check method-channel *argument keys*, not just method names.

A mismatched key is invisible to both compilers: Kotlin's call.argument<T>("x")
just returns null, and Dart's map[...] just returns null. It fails silently at
runtime, which is exactly the class of bug worth catching mechanically.

Two directions:
  Dart -> Kotlin   invokeMethod('m', {'k': v})   vs   call.argument<T>("k")
  Kotlin -> Dart   invokeOnMain("m", mapOf("k" to v))  vs  data['k']
"""
import pathlib
import re
import sys

KT = pathlib.Path("android/app/src/main/kotlin")
LIB = pathlib.Path("lib")


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def kotlin_case_bodies():
    """Map a channel method name to the Kotlin source of its `when` branch."""
    text = "\n".join(read(p) for p in KT.rglob("*.kt"))
    bodies = {}
    starts = [
        (m.group(1), m.end())
        for m in re.finditer(r'^\s*"([A-Za-z0-9_]+)"\s*->\s*\{', text, re.M)
    ]
    for name, start in starts:
        depth, i = 1, start
        while i < len(text) and depth:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        bodies[name] = text[start:i]
    return bodies


def dart_invocations():
    """Map a channel method name to the set of argument keys Dart sends."""
    text = "\n".join(read(p) for p in LIB.rglob("*.dart"))
    calls = {}
    for m in re.finditer(
        r"invokeMethod\(\s*'([A-Za-z0-9_]+)'\s*(?:,\s*\{(.*?)\})?\s*\)",
        text,
        re.S,
    ):
        name, args = m.group(1), m.group(2) or ""
        calls.setdefault(name, set()).update(re.findall(r"'([A-Za-z0-9_]+)'\s*:", args))
    return calls


def main():
    bodies = kotlin_case_bodies()
    calls = dart_invocations()
    problems = 0

    print("=== Dart -> Kotlin argument keys ===")
    for name, sent in sorted(calls.items()):
        body = bodies.get(name)
        if body is None:
            continue
        expected = set(re.findall(r'\.argument<[^>]*>\(\s*"([A-Za-z0-9_]+)"\s*\)', body))
        missing = expected - sent  # Kotlin reads it, Dart never sends it
        unused = sent - expected  # Dart sends it, Kotlin never reads it
        if missing or unused:
            problems += 1
            print("  %s" % name)
            if missing:
                print("      kotlin reads but dart never sends: %s" % sorted(missing))
            if unused:
                print("      dart sends but kotlin never reads: %s" % sorted(unused))
    if not problems:
        print("  none")

    # Kotlin -> Dart: keys inside mapOf(...) passed to invokeOnMain / invokeMethod
    print()
    print("=== Kotlin -> Dart payload keys (informational) ===")
    text = "\n".join(read(p) for p in KT.rglob("*.kt"))
    dart_text = "\n".join(read(p) for p in LIB.rglob("*.dart"))
    for m in re.finditer(
        r'invoke(?:OnMain|Method)\(\s*"([A-Za-z0-9_]+)"\s*,\s*mapOf\((.*?)\)\s*\)',
        text,
        re.S,
    ):
        name, payload = m.group(1), m.group(2)
        keys = set(re.findall(r'"([A-Za-z0-9_]+)"\s+to\s', payload))
        unread = {k for k in keys if ("'%s'" % k) not in dart_text}
        if unread:
            problems += 1
            print("  %s -> dart never reads: %s" % (name, sorted(unread)))
    print("  (no output above means every key is referenced somewhere in Dart)")

    print()
    print("total problems:", problems)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
