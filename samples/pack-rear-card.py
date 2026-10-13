#!/usr/bin/env python3
"""Pack samples/rear-visible into one input ZIP (index.html + main.js).

Usage:
  python3 samples/pack-rear-card.py
  python3 samples/pack-rear-card.py /tmp/rear-visible.zip

Does not calculate or print a hash.
"""
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
src = root / "rear-visible"
out = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "rear-visible.zip"

with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in ("index.html", "main.js"):
        archive.write(src / name, name)

print(f"已生成 {out}")
