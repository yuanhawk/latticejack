#!/usr/bin/env python3
"""Recomputes this directory's percentages from the committed raw data
(functions-capture-periodic_sampling.csv.gz) - the same aggregation used
to produce the numbers in README.md, so the finding is checkable from the
repo, not just asserted. Added after an independent audit noted this
directory originally had no raw data committed at all, unlike every other
benchmark in this project.

Usage: python3 analyze.py
"""
import csv
import gzip
from collections import Counter
from pathlib import Path

HERE = Path(__file__).parent
CSV_GZ = HERE / "functions-capture-periodic_sampling.csv.gz"


def main():
    by_symbol = Counter()
    by_image = Counter()
    total = 0

    with gzip.open(CSV_GZ, "rt", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                n = float(row.get("Periodic Samples", 0) or 0)
            except ValueError:
                n = 0
            symbol = (row.get("symbol", "") or "?").strip()
            image = row.get("image", "") or "?"
            by_symbol[(image, symbol)] += n
            by_image[image] += n
            total += n

    print(f"Total samples: {total:.0f}\n")

    print("=== By image ===")
    for img, n in by_image.most_common(10):
        print(f"{n/total*100:6.2f}%  {n:10.0f}  {img}")

    interpreter = sum(n for (img, sym), n in by_symbol.items() if sym == "Interpreter")
    bc_total = sum(n for (img, sym), n in by_symbol.items() if "bouncycastle" in sym.lower())
    compiler_total = sum(
        n for (img, sym), n in by_symbol.items() if "Phase" in sym or "Compile" in sym or "compil" in sym.lower()
    )

    print(f"\nInterpreter (not-yet-JIT-compiled): {interpreter/total*100:.2f}%")
    print(f"All org.bouncycastle.*:             {bc_total/total*100:.2f}%")
    print(f"Compiler-pass-attributed (substring match): {compiler_total/total*100:.2f}%")


if __name__ == "__main__":
    main()
