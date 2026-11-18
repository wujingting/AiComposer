#!/usr/bin/env python3
"""Download the YOLOX object detector (Megvii, Apache-2.0, 80 COCO classes) into core/src/main/assets/detector.onnx.

    python tools/get_detector_model.py            # yolox_s: 36 MB, COCO AP 40.5 (default, good balance)
    python tools/get_detector_model.py --size m   # yolox_m: 97 MB, AP 46.9, ~3x slower
    python tools/get_detector_model.py --size tiny  # yolox_tiny: 20 MB, AP 32.8, fast on older phones

Then rebuild the app. Without this file the app falls back to ML Kit's object detector.
"""
import argparse
import pathlib
import sys
import urllib.request

BASE = "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/"
SIZES = {"nano": "yolox_nano.onnx", "tiny": "yolox_tiny.onnx", "s": "yolox_s.onnx", "m": "yolox_m.onnx", "l": "yolox_l.onnx"}
OUT = pathlib.Path(__file__).resolve().parent.parent / "core" / "src" / "main" / "assets" / "detector.onnx"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", choices=SIZES, default="s")
    args = ap.parse_args()
    url = BASE + SIZES[args.size]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".tmp")
    print(f"Downloading {url}")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done * 100 // total}%", end="", flush=True)
    print()
    try:
        import onnxruntime as ort
        s = ort.InferenceSession(str(tmp))
        shape_in, shape_out = s.get_inputs()[0].shape, s.get_outputs()[0].shape
        if shape_in[1] != 3 or shape_out[-1] != 85:
            sys.exit(f"Unexpected model shape: input {shape_in}, output {shape_out}")
        print(f"Checked: input {shape_in}, output {shape_out}")
    except ImportError:
        print("(onnxruntime not installed, skipped the shape check)")
    tmp.replace(OUT)
    print(f"Saved {OUT} ({OUT.stat().st_size / 1e6:.0f} MB). Rebuild the app.")


if __name__ == "__main__":
    main()
