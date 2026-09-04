"""Check that every Dart widget instantiation supplies all `required` params.

A missing required named argument is a compile error. `flutter analyze` would
catch it, but analyze has never been run on this tree, and _SecurityCard gained
two parameters recently, so the call sites are worth confirming.

Parameters are split depth-aware rather than by regex, because a regex that
expects a trailing comma silently drops the final parameter and then reports it
as "unknown" at every call site.
"""
import pathlib
import re
import sys

LIB = pathlib.Path("lib")


def blank(match):
    """Replace a span with spaces, keeping newlines so line numbers stay true."""
    return "".join(c if c == "\n" else " " for c in match.group(0))


def read(p):
    """Source with comments blanked out.

    A comma inside a comment would otherwise split an argument list and make a
    supplied parameter look missing — which is exactly what this tool reported
    for _StatusRow in dialer_tab.dart before comments were stripped.
    """
    text = p.read_text(encoding="utf-8", errors="replace")
    text = re.sub(r"/\*.*?\*/", blank, text, flags=re.S)
    text = re.sub(r"//[^\n]*", blank, text)
    return text


def matching(text, open_at, opener, closer):
    """Index just past the closer matching the opener at open_at."""
    depth, i = 1, open_at + 1
    while i < len(text) and depth:
        if text[i] == opener:
            depth += 1
        elif text[i] == closer:
            depth -= 1
        i += 1
    return i


def split_top_level(s):
    """Split on commas that are not nested inside (), <>, {} or [] ."""
    parts, depth, current = [], 0, []
    for c in s:
        if c in "(<{[":
            depth += 1
        elif c in ")>}]":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(c)
    if "".join(current).strip():
        parts.append("".join(current))
    return [p.strip() for p in parts if p.strip()]


def constructors(text):
    """widget name -> (required names, optional names)."""
    found = {}
    for m in re.finditer(
        r"class\s+(_?[A-Za-z0-9_]+)\s+extends\s+(?:Stateless|Stateful)Widget", text
    ):
        name = m.group(1)
        tail = text[m.end():]
        ctor = re.search(r"(?:const\s+)?" + re.escape(name) + r"\s*\(\s*\{", tail)
        if not ctor:
            continue
        brace = tail.index("{", ctor.start())
        end = matching(tail, brace, "{", "}")
        params = tail[brace + 1:end - 1]

        req, opt = set(), set()
        for part in split_top_level(params):
            if part.startswith("super."):
                continue
            is_required = part.startswith("required ")
            body = part[len("required "):] if is_required else part
            body = body.split("=")[0].strip()
            pname = body.split(".")[-1].split()[-1].strip()
            if not pname or pname in ("key",):
                continue
            (req if is_required else opt).add(pname)
        found[name] = (req, opt)
    return found


def main():
    texts = {p: read(p) for p in LIB.rglob("*.dart")}
    ctors = {}
    for text in texts.values():
        ctors.update(constructors(text))

    missing_problems = 0
    unknown_problems = 0
    for path, text in texts.items():
        for name, (req, opt) in ctors.items():
            for m in re.finditer(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"\s*\(", text):
                open_paren = m.end() - 1
                end = matching(text, open_paren, "(", ")")
                args = text[open_paren + 1:end - 1]
                if args.lstrip().startswith("{"):
                    continue  # the declaration itself
                supplied = set()
                for part in split_top_level(args):
                    label = re.match(r"([A-Za-z0-9_]+)\s*:", part)
                    if label:
                        supplied.add(label.group(1))
                line = text[:m.start()].count("\n") + 1
                for miss in sorted(req - supplied):
                    missing_problems += 1
                    print("  MISSING  %s:%d  %s.%s" % (path, line, name, miss))
                for extra in sorted(supplied - req - opt - {"key"}):
                    unknown_problems += 1
                    print("  UNKNOWN  %s:%d  %s.%s" % (path, line, name, extra))

    print("=== widget constructor arguments ===")
    print("  constructors parsed :", len(ctors))
    print("  missing required    :", missing_problems)
    print("  unknown arguments   :", unknown_problems)
    return 1 if (missing_problems or unknown_problems) else 0


if __name__ == "__main__":
    sys.exit(main())
