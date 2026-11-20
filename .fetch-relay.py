#!/usr/bin/env python3
"""Fetch-relay helper: solves Anubis PoW challenge, then fetches real pages."""
import hashlib
import json
import re
import sys
import time
import urllib.request
import urllib.parse
import http.cookiejar

BASE = "https://animealpha.cc"
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
CHALLENGE_RE = re.compile(
    r'<script id="anubis_challenge" type="application/json">(.*?)</script>',
    re.S,
)

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    })
    with opener.open(req, timeout=timeout) as resp:
        return resp.geturl(), resp.read().decode("utf-8", "replace")


def solve_anubis(html, page_url):
    m = CHALLENGE_RE.search(html)
    if not m:
        return False
    data = json.loads(m.group(1))
    rules = data["rules"]
    challenge = data["challenge"]
    difficulty = int(rules["difficulty"])
    random_data = challenge["randomData"]
    challenge_id = challenge["id"]
    print(f"  solving anubis: algo={rules['algorithm']} difficulty={difficulty}", flush=True)
    t0 = time.time()
    prefix = "0" * difficulty
    nonce = 0
    while True:
        h = hashlib.sha256((random_data + str(nonce)).encode()).hexdigest()
        if h.startswith(prefix):
            break
        nonce += 1
    elapsed_ms = int((time.time() - t0) * 1000)
    print(f"  solved nonce={nonce} hash={h} in {elapsed_ms}ms", flush=True)
    params = urllib.parse.urlencode({
        "id": challenge_id,
        "response": h,
        "nonce": str(nonce),
        "redir": page_url,
        "elapsedTime": str(max(elapsed_ms, 1)),
    })
    pass_url = f"{BASE}/.within.website/x/cmd/anubis/api/pass-challenge?{params}"
    # follow redirect manually to keep cookies
    req = urllib.request.Request(pass_url, headers={"User-Agent": UA})
    opener.open(req, timeout=30).read()
    print(f"  clearance cookies: {[c.name for c in cj]}", flush=True)
    return True


def get(path):
    url = BASE + path
    for attempt in range(4):
        final, html = fetch(url)
        if "anubis_challenge" in html:
            if not solve_anubis(html, final):
                break
            continue
        return html
    raise RuntimeError(f"could not bypass challenge for {path}")


if __name__ == "__main__":
    out_dir = sys.argv[1]
    paths = [p for p in sys.argv[2:] if p]
    import os
    os.makedirs(out_dir, exist_ok=True)
    log_lines = []
    for i, p in enumerate(paths, 1):
        try:
            html = get(p)
            name = re.sub(r"[^A-Za-z0-9._-]", "_", p)[:80]
            with open(f"{out_dir}/{i}_{name}", "w") as f:
                f.write(html)
            msg = f"OK {p} -> {len(html)} bytes"
            print(msg, flush=True)
            log_lines.append(msg)
        except Exception as e:
            import traceback
            msg = f"FAIL {p}: {e}\n{traceback.format_exc()}"
            print(msg, flush=True)
            log_lines.append(msg)
    with open(f"{out_dir}/_log.txt", "w") as f:
        f.write("\n\n".join(log_lines))
