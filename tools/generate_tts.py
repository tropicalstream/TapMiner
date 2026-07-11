#!/usr/bin/env python3
"""
Pre-generate TapMiner's voice lines with fish.audio S2.1 Pro
(free developer tier: https://fish.audio/blog/s2-1-pro-free-api/).

TWO voices, each its own fish.audio model:
  - The young indentured miner (phrases.json):
      https://fish.audio/app/m/b5f4515fd395410b9ed3aef6fa51d9a0/
  - The moon's natives (phrases_alien.json) — attract screen + coffee breaks:
      https://fish.audio/app/m/f48d143a59a946ab87c0130fd081f349/

Usage:
  export FISH_API_KEY=...          # from https://fish.audio developer console
  python3 tools/generate_tts.py    # writes app/src/main/assets/tts/<id>.mp3
  ./gradlew assembleDebug          # clips ship inside the APK

A phrase may be a single string ("id": "...") or a list of variants
("id": ["...", "...", ...]) that the app picks between at random — those
generate one file per variant, named <id>_<index>.mp3.

Already-generated phrases are skipped; delete a file to force regeneration.
Requires: pip install requests
"""

import json
import os
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("pip install requests")

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app/src/main/assets/tts"
API_URL = "https://api.fish.audio/v1/tts"

# (phrases file, fish.audio voice model id)
VOICE_SETS = [
    ("app/src/main/assets/phrases.json", "b5f4515fd395410b9ed3aef6fa51d9a0"),        # the miner
    ("app/src/main/assets/phrases_alien.json", "f48d143a59a946ab87c0130fd081f349"),  # the natives
]


def flatten(phrases: dict) -> list:
    jobs = []
    for pid, value in phrases.items():
        if isinstance(value, list):
            for i, text in enumerate(value):
                jobs.append((f"{pid}_{i}", text))
        else:
            jobs.append((pid, value))
    return jobs


def synth(api_key: str, text: str, model_id: str) -> bytes | None:
    resp = requests.post(
        API_URL,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Model": "s1",  # server maps to the current S2.1 Pro engine
        },
        json={
            "text": text,
            "reference_id": model_id,
            "format": "mp3",
            "mp3_bitrate": 64,
            "normalize": True,
            "latency": "normal",
        },
        timeout=60,
    )
    if resp.status_code == 200 and resp.content:
        return resp.content
    print(f" FAIL: HTTP {resp.status_code} {resp.text[:120]}")
    return None


def main() -> None:
    api_key = os.environ.get("FISH_API_KEY")
    if not api_key:
        sys.exit("Set FISH_API_KEY first (free tier: fish.audio developer console)")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    done = skipped = failed = 0

    for rel, model_id in VOICE_SETS:
        path = ROOT / rel
        if not path.exists():
            print(f"skip missing {rel}")
            continue
        for stem, text in flatten(json.loads(path.read_text())):
            out = OUT_DIR / f"{stem}.mp3"
            if out.exists() and out.stat().st_size > 0:
                skipped += 1
                continue
            data = synth(api_key, text, model_id)
            if data:
                out.write_bytes(data)
                print(f"  ok  {stem}: {text[:56]}")
                done += 1
            else:
                failed += 1
            time.sleep(0.4)  # be polite to the free tier

    print(f"\ngenerated={done} skipped={skipped} failed={failed}")
    print(f"clips in {OUT_DIR}")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
