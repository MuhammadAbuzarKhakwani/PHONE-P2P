"""Check Kotlin named arguments against the declared parameter names.

Several signatures changed during this work (SecureHandshake's constructor and
acceptOffer, CellularGateway.readStatus, GatewaySessionHandler's constructor,
SessionKeys.derive, PairingPrompt.onPairingFinished). A named argument that no
longer exists is a compile error, and no compiler has run here.

Only project-declared functions and constructors are checked; calls into the
Android framework or the stdlib are ignored, since their signatures are unknown
to this script.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path("android/app/src")
COMMENT_BLOCK = re.compile(r"/\*.*?\*/", re.S)
COMMENT_LINE = re.compile(r"^\s*//.*$", re.M)


def blank(match):
    """Replace a span with spaces, keeping newlines so line numbers stay true."""
    return "".join(c if c == chr(10) else " " for c in match.group(0))


def strip_strings(text):
    """Blank out string and char literals.

    Without this the tool parses prose. A toString() containing
    "PairingRecord(peer=..., name=...)" reads as a call with named arguments that
    do not exist, and a diagnostic message containing "bitrateBps=..." does the
    same. Every earlier hit from this checker was that, not a real defect.
    """
    out = []
    i, n = 0, len(text)
    triple = chr(34) * 3
    while i < n:
        if text.startswith(triple, i):
            end = text.find(triple, i + 3)
            end = n if end == -1 else end + 3
            out.append("".join(c if c == chr(10) else " " for c in text[i:end]))
            i = end
            continue
        c = text[i]
        if c == chr(34):
            j = i + 1
            while j < n and text[j] != chr(34):
                j += 2 if text[j] == chr(92) else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(p):
    text = p.read_text(encoding="utf-8", errors="replace")
    text = COMMENT_BLOCK.sub(blank, text)
    text = COMMENT_LINE.sub(blank, text)
    text = strip_strings(text)
    return text


def matching(text, open_at, opener, closer):
    depth, i = 1, open_at + 1
    while i < len(text) and depth:
        if text[i] == opener:
            depth += 1
        elif text[i] == closer:
            depth -= 1
        i += 1
    return i


def split_top_level(s):
    parts, depth, cur = [], 0, []
    for c in s:
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    if "".join(cur).strip():
        parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def param_names(param_text):
    names = set()
    for part in split_top_level(param_text):
        part = re.sub(r"^(?:@\w+\s+)*", "", part)
        part = re.sub(r"^(?:private|internal|public|protected)\s+", "", part)
        part = re.sub(r"^(?:override\s+)?(?:val|var)\s+", "", part)
        m = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*:", part)
        if m:
            names.add(m.group(1))
    return names


def collect_signatures(files):
    """name -> set of allowed named-argument names (union across overloads)."""
    sigs = {}
    for p in files:
        text = read(p)
        # functions
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", text):
            op = m.end() - 1
            end = matching(text, op, "(", ")")
            sigs.setdefault(m.group(1), set()).update(param_names(text[op + 1:end - 1]))
        # primary constructors
        for m in re.finditer(
            r"\b(?:data\s+|value\s+|sealed\s+|abstract\s+|open\s+|internal\s+|private\s+)*class\s+"
            r"([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]*>\s*)?(?:internal\s+|private\s+)?\(",
            text,
        ):
            op = text.index("(", m.end() - 1)
            end = matching(text, op, "(", ")")
            sigs.setdefault(m.group(1), set()).update(param_names(text[op + 1:end - 1]))
    return sigs


def main():
    files = sorted(ROOT.rglob("*.kt"))
    sigs = collect_signatures(files)
    problems = 0

    for p in files:
        text = read(p)
        for m in re.finditer(r"(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*\(", text):
            name = m.group(1)
            if name not in sigs:
                continue
            if name in ("if", "when", "while", "for", "catch", "return", "fun", "class"):
                continue
            op = m.end() - 1
            end = matching(text, op, "(", ")")
            args = text[op + 1:end - 1]
            if ":" in args and re.search(r"\bfun\s+" + re.escape(name) + r"\s*\($", text[:m.end()]):
                continue  # declaration
            used = set()
            for part in split_top_level(args):
                lm = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)", part)
                if lm:
                    used.add(lm.group(1))
            unknown = used - sigs[name]
            if unknown:
                problems += 1
                line = text[:m.start()].count("\n") + 1
                print("  %s:%d  %s(...) unknown named args: %s"
                      % (p, line, name, sorted(unknown)))

    print("=== Kotlin named arguments ===")
    print("  signatures collected :", len(sigs))
    print("  problems             :", problems)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
