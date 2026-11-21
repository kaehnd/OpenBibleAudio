#!/usr/bin/env python3
"""Download a BSB narration from openbible.com/audio/<narrator>/ (resumable).

Usage: python tools/download_openbible_audio.py <narrator> [dest]
  narrator: gilbert | souer   (any openbible.com/audio/ folder works)
  dest:     defaults to bible_audio/bsb_<narrator>

Only the per-chapter MP3s are fetched. The whole-testament zips some folders offer
(e.g. BSB_00_Souer_OT.zip) duplicate them and are skipped.
"""
import re, sys, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
    sys.exit(__doc__)
NARRATOR = sys.argv[1].strip("/").lower()
BASE = f"https://openbible.com/audio/{NARRATOR}/"
DEST = Path(sys.argv[2] if len(sys.argv) > 2 else f"bible_audio/bsb_{NARRATOR}")
UA = {"User-Agent": "OpenBibleAudio-downloader"}

def get(url, method="GET"):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA, method=method), timeout=60)

def head(name):
    try:
        with get(BASE + urllib.parse.quote(name), "HEAD") as r:
            return name, int(r.headers["Content-Length"])
    except Exception:
        return name, None

def fetch(item):
    name, size = item
    out = DEST / name
    if out.exists() and (size is None or out.stat().st_size == size):
        return name, "skip"
    tmp = out.with_suffix(out.suffix + ".part")
    err = None
    for _ in range(3):
        try:
            with get(BASE + urllib.parse.quote(name)) as r, open(tmp, "wb") as f:
                while chunk := r.read(1 << 16):
                    f.write(chunk)
            if size is not None and tmp.stat().st_size != size:
                raise IOError(f"got {tmp.stat().st_size} bytes, expected {size}")
            tmp.replace(out)
            return name, "ok"
        except Exception as e:
            err = e
    return name, f"FAIL {err}"

html = get(BASE).read().decode("utf-8", "replace")
names = [n for n in dict.fromkeys(re.findall(r'<a href="([^"?/][^"]*)"', html))
         if n.lower().endswith(".mp3")]
if not names:
    sys.exit(f"no .mp3 files listed at {BASE}")
DEST.mkdir(parents=True, exist_ok=True)
print(f"{len(names)} files from {BASE} -> {DEST}")
with ThreadPoolExecutor(8) as ex:
    # HEAD for sizes so partial/truncated files are detected and re-fetched.
    items = list(ex.map(head, names))
    fails = 0
    for i, (n, s) in enumerate(ex.map(fetch, items), 1):
        if s.startswith("FAIL"):
            fails += 1
            print(n, s)
        elif i % 50 == 0:
            print(f"{i}/{len(names)}", flush=True)
print("done, failures:", fails)
sys.exit(1 if fails else 0)
