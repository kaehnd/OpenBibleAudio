#!/usr/bin/env python3
"""Download all files from the openbible.com Gilbert BSB audio index (resumable)."""
import re, sys, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "https://openbible.com/audio/gilbert/"
DEST = Path(sys.argv[1] if len(sys.argv) > 1 else "bible_audio/bsb_gilbert")
UA = {"User-Agent": "OpenBibleAudio-downloader"}

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)

def fetch(item):
    name, size = item
    out = DEST / name
    if out.exists() and (size is None or out.stat().st_size == size):
        return name, "skip"
    tmp = out.with_suffix(out.suffix + ".part")
    for attempt in range(3):
        try:
            with get(BASE + urllib.parse.quote(name)) as r, open(tmp, "wb") as f:
                while chunk := r.read(1 << 16):
                    f.write(chunk)
            tmp.replace(out)
            return name, "ok"
        except Exception as e:
            err = e
    return name, f"FAIL {err}"

html = get(BASE).read().decode("utf-8", "replace")
names = [n for n in dict.fromkeys(re.findall(r'<a href="([^"?/][^"]*)"', html))]
DEST.mkdir(parents=True, exist_ok=True)
print(f"{len(names)} files -> {DEST}")
# HEAD for sizes so partial/truncated files are detected
def head(n):
    try:
        req = urllib.request.Request(BASE + urllib.parse.quote(n), headers=UA, method="HEAD")
        return n, int(urllib.request.urlopen(req, timeout=60).headers["Content-Length"])
    except Exception:
        return n, None
with ThreadPoolExecutor(8) as ex:
    items = list(ex.map(head, names))
    fails = 0
    for i, (n, s) in enumerate(ex.map(fetch, items), 1):
        if s.startswith("FAIL"):
            fails += 1
            print(n, s)
        elif i % 50 == 0:
            print(f"{i}/{len(names)}")
print("done, failures:", fails)
