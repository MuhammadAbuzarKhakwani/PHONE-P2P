# Static checks

Four small Python scripts that check things **no compiler in this project will
catch**, plus a couple of things a compiler would catch but which nobody has run
yet.

They exist because this codebase was written without a working toolchain, and
between them they found three real defects that a clean compile would not have
prevented.

```bash
cd <repo root>
python tools/static_checks/run_all.py        # exits non-zero on any finding
```

Requires only Python 3.8+. No dependencies.

**These are a safety net, not a substitute for `flutter analyze` and
`./gradlew :app:testDebugUnitTest`.** They prove that names resolve, keys agree
and literals terminate; they cannot prove that types match.

---

## What each one does

### `check_channel_keys.py` — the highest-value one

Cross-checks method-channel **argument keys**, in both directions.

A mismatched key is invisible to both compilers: Kotlin's `call.argument<T>("x")`
just returns null, and Dart's `map['x']` just returns null. The feature silently
does nothing at runtime, on a device, with no error anywhere.

### `check_kotlin_named_args.py`

Checks Kotlin named arguments against the declared parameter names of
project-declared functions and constructors. Renaming a parameter without
updating a call site is a compile error, and several signatures changed during
development.

Strips comments and string literals first — a `toString()` containing
`"PairingRecord(peer=..., name=...)"` otherwise parses as a call with named
arguments that do not exist.

### `check_widget_args.py`

Checks that every Dart widget instantiation supplies all `required` parameters
and no unknown ones. Comments are stripped, because a comma inside prose
otherwise splits an argument list and makes a supplied parameter look missing.

### `check_strings.py`

Finds unterminated string literals in Dart and Kotlin.

Written after an `\n` escape was mangled into a real newline inside a
single-quoted Dart string — a compile error Dart cannot recover from. Kotlin
backtick identifiers (`` fun `a test name with an apostrophe's` `` ) are
recognised and skipped.

---

## Known benign finding

`check_channel_keys.py` reports that `onPermissionDenied` sends a `permissions`
key that Dart never reads. That is pre-existing behaviour: the Dart side
deliberately shows the human-readable `capabilities` list instead of raw
permission strings. It is reported rather than suppressed, because silently
ignoring an unread key is how a real mismatch would get missed.
