#!/usr/bin/env python3
"""Validates generate_cbom.py's output against the real, committed
CycloneDX 1.6 JSON schema (cyclonedx-1.6.schema.json, downloaded from
CycloneDX/specification - not hand-written, not assumed).

This exists because an earlier version of this project's documentation
claimed the generated CBOMs were "validated against the real published
schema" when the validation had only ever been run once, ad hoc, outside
the repo (in a scratch venv, against a schema file that was never
committed) - true in the moment, but not a claim anyone could reproduce
from the repo alone, and not caught by anything if a later edit to
generate_cbom.py broke schema conformance. Caught by an independent
Opus+Fable audit of this project's own claims; fixed by committing the
schema and this script rather than just softening the prose.

Usage:
    python3 validate_cbom.py before.cbom.json after.cbom.json
    ./run cbom-validate           # validates the two checked-in samples
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).parent
SCHEMA_PATH = HERE / "cyclonedx-1.6.schema.json"


def main():
    try:
        import jsonschema
    except ImportError:
        print(
            "ERROR: this script needs the 'jsonschema' package "
            "(pip install jsonschema) - not a stdlib module, and "
            "deliberately not vendored into this repo.",
            file=sys.stderr,
        )
        sys.exit(2)

    targets = sys.argv[1:] or [str(HERE / "before.cbom.json"), str(HERE / "after.cbom.json")]
    schema = json.loads(SCHEMA_PATH.read_text())

    failures = 0
    for target in targets:
        bom = json.loads(Path(target).read_text())
        try:
            jsonschema.validate(bom, schema)
            print(f"VALID   {target}")
        except jsonschema.ValidationError as e:
            failures += 1
            print(f"INVALID {target}: {e.message} (at {'/'.join(str(p) for p in e.path)})", file=sys.stderr)

    if failures:
        sys.exit(1)
    print(f"\nAll {len(targets)} file(s) conform to the CycloneDX 1.6 schema.")


if __name__ == "__main__":
    main()
