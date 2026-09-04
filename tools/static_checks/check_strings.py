"""Find unterminated string literals in Dart and Kotlin sources.

Written because a literal newline was accidentally introduced inside a
single-quoted Dart string, which is a compile error Dart cannot recover from.
If one such mangling happened, others may have.
"""
import pathlib
import re
import sys

BACKSLASH = chr(92)
SQ = chr(39)
DQ = chr(34)


def strip_line_comment(line):
    """Drop a // comment that is not inside a string literal."""
    out = []
    in_string = False
    quote = ""
    i = 0
    while i < len(line):
        c = line[i]
        if in_string:
            if c == BACKSLASH:
                out.append(c)
                if i + 1 < len(line):
                    out.append(line[i + 1])
                i += 2
                continue
            if c == quote:
                in_string = False
        else:
            if c in (SQ, DQ):
                in_string = True
                quote = c
            elif c == "/" and i + 1 < len(line) and line[i + 1] == "/":
                break
        out.append(c)
        i += 1
    return "".join(out)


def has_unterminated_string(line):
    """True if a quote opens on this line and never closes."""
    in_string = False
    quote = ""
    i = 0
    while i < len(line):
        c = line[i]
        if in_string:
            if c == BACKSLASH:
                i += 2
                continue
            if c == quote:
                in_string = False
        else:
            if c in (SQ, DQ):
                in_string = True
                quote = c
        i += 1
    return in_string


def scan_dart(root):
    problems = []
    for path in sorted(pathlib.Path(root).rglob("*.dart")):
        text = path.read_text(encoding="utf-8")
        in_block_comment = False
        for number, raw in enumerate(text.split("\n"), 1):
            stripped = raw.strip()
            if "/*" in raw and "*/" not in raw:
                in_block_comment = True
            if in_block_comment:
                if "*/" in raw:
                    in_block_comment = False
                continue
            if stripped.startswith(("///", "//", "*")):
                continue
            if SQ * 3 in raw or DQ * 3 in raw:
                continue
            if has_unterminated_string(strip_line_comment(raw)):
                problems.append((str(path), number, stripped[:100]))
    return problems


def scan_kotlin(root):
    problems = []
    raw_string = DQ * 3
    for path in sorted(pathlib.Path(root).rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        # Blank out raw strings; their newlines are legal.
        text = re.sub(raw_string + r"(?:.|\n)*?" + raw_string, DQ + DQ, text)
        for number, raw in enumerate(text.split("\n"), 1):
            stripped = raw.strip()
            if stripped.startswith(("//", "*", "/*")):
                continue
            # Kotlin backtick identifiers may contain apostrophes, e.g.
            #   fun `each side accepts the other's confirmation`()
            # which is legal and must not be read as an unterminated literal.
            without_backticks = re.sub("`[^`]*`", "``", raw)
            if has_unterminated_string(strip_line_comment(without_backticks)):
                problems.append((str(path), number, stripped[:100]))
    return problems


def report(title, problems):
    print("=== %s ===" % title)
    if problems:
        for path, number, text in problems:
            print("  %s:%d  %s" % (path, number, text))
    else:
        print("  none")
    return len(problems)


if __name__ == "__main__":
    total = 0
    total += report("Dart: unterminated string literals", scan_dart("lib"))
    print()
    total += report(
        "Kotlin: unterminated string literals", scan_kotlin("android/app/src")
    )
    print()
    print("total problems:", total)
    sys.exit(1 if total else 0)
