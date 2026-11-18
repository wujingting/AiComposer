#!/usr/bin/env python3
"""Download the YOLOX object detector (Megvii, Apache-2.0, 80 COCO classes) into core/src/main/assets/detector.onnx.

    python tools/get_detector_model.py            # yolox_s: 36 MB, COCO AP 40.5 (default, good balance)
    python tools/get_detector_model.py --size m   # yolox_m: 97 MB, AP 46.9, ~3x slower
    python tools/get_detector_model.py --size tiny  # yolox_tiny: 20 MB, AP 32.8, fast on older phones

Then rebuild the app. Without this file the app falls back to ML Kit's object detector.
The download is always over verified HTTPS, and models with a known SHA-256 are checked before they are used.
"""
import argparse
import hashlib
import pathlib
import ssl
import sys
import urllib.request

BASE = "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/"
SIZES = {"nano": "yolox_nano.onnx", "tiny": "yolox_tiny.onnx", "s": "yolox_s.onnx", "m": "yolox_m.onnx", "l": "yolox_l.onnx"}
# SHA-256 of the release files (the GitHub release publishes no digests). Add a size here once its hash is known.
SHA256 = {
    "s": "c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063",  # 35,858,002 bytes
}
OUT = pathlib.Path(__file__).resolve().parent.parent / "core" / "src" / "main" / "assets" / "detector.onnx"


def tls_context() -> ssl.SSLContext:
    """Certificate-verifying context: certifi's CA bundle when installed, otherwise the system store. Never unverified."""
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", choices=SIZES, default="s")
    args = ap.parse_args()
    url = BASE + SIZES[args.size]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".tmp")
    print(f"Downloading {url}")
    digest = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, context=tls_context()) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            digest.update(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done * 100 // total}%", end="", flush=True)
    print()

    # Verify the hash before anything parses the file.
    expected = SHA256.get(args.size)
    actual = digest.hexdigest()
    if expected is None:
        print(f"WARNING: no known SHA-256 for --size {args.size}; the file is NOT verified (sha256 {actual}).")
    elif actual != expected:
        tmp.unlink(missing_ok=True)
        sys.exit(f"SHA-256 mismatch: expected {expected}, got {actual}. Download discarded.")
    else:
        print(f"Checked: SHA-256 {actual}")

    try:
        import onnxruntime as ort
        s = ort.InferenceSession(str(tmp))
        shape_in, shape_out = s.get_inputs()[0].shape, s.get_outputs()[0].shape
        del s
        if shape_in[1] != 3 or shape_out[-1] != 85:
            tmp.unlink(missing_ok=True)
            sys.exit(f"Unexpected model shape: input {shape_in}, output {shape_out}")
        print(f"Checked: input {shape_in}, output {shape_out}")
    except ImportError:
        print("(onnxruntime not installed, skipped the shape check)")
    tmp.replace(OUT)
    print(f"Saved {OUT} ({OUT.stat().st_size / 1e6:.0f} MB). Rebuild the app.")


if __name__ == "__main__":
    main()
