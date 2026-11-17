# Photo Picker (PC)

**English** | [中文](README.zh-CN.md) | [日本語](README.ja.md)

Scores a folder of photos with the same AI aesthetic model as the AI Composer app (CLIP ViT-B/32 + LAION aesthetic head), picks the best ones, and copies them to another folder. Your original photos are never modified.

- Reads JPG / PNG / TIFF / WebP and **Sony ARW** (uses the JPEG preview embedded in the ARW, no extra library needed)
- A JPG and an ARW with the same name (`DSC01234.JPG` + `DSC01234.ARW`) count as one photo: scored once, and both files are copied when picked (`.xmp` sidecars too)
- **Removes near-duplicates**: burst shots and the same scene shot twice are detected and only the best one is kept
- Writes `scores.csv` with the scores of every photo (opens in Excel)
- Messages follow the system language (Chinese / Japanese / English)

## Setup (once)
1. Python 3.10 or newer.
2. Install the libraries:
   ```bash
   pip install -r tools/photo_picker/requirements.txt
   ```
3. Generate the model (if you have not already for the app):
   ```bash
   pip install torch open_clip_torch onnx onnxruntime
   python tools/export_clip_model.py
   ```
   This creates `core/src/main/assets/clip_image.onnx` and `clip_meta.json`, which the picker uses by default.

## Usage
```bash
# Pick the best 20% (default) and copy them to D:/Photo/2026-11-17/_picked
python tools/photo_picker/pick_photos.py D:/Photo/2026-11-17

# Pick the best 30 photos, including subfolders, with the rank in the file name (001_DSC01234.JPG)
python tools/photo_picker/pick_photos.py D:/Photo/trip --top 30 --recursive --rank-names

# Every photo with an aesthetic score of 6.0 or more, into another folder
python tools/photo_picker/pick_photos.py D:/Photo/trip --min-score 6.0 -o D:/Photo/trip_best

# Only score (no copying), to look at scores.csv first
python tools/photo_picker/pick_photos.py D:/Photo/trip --dry-run
```

| Option | Meaning |
|---|---|
| `--top N` | Pick the N best photos |
| `--top-percent P` | Pick the best P percent (default 20) |
| `--min-score S` | Pick every photo with an aesthetic score ≥ S |
| `-o FOLDER` | Output folder (default: `_picked` inside the photo folder) |
| `-r`, `--recursive` | Include subfolders (their structure is kept in the output) |
| `--similarity X` | Photos at least this similar count as duplicates (default 0.94; lower = more photos treated as duplicates) |
| `--no-dedupe` | Keep near-duplicates too |
| `--min-sharpness X` | Skip photos with sharpness below X (default 0 = off) |
| `--rank-names` | Prefix copied files with their rank |
| `--dry-run` | Score and write `scores.csv` only |
| `--lang zh/ja/en` | Message language |
| `--model-dir FOLDER` | Folder with `clip_image.onnx` + `clip_meta.json` |

`--top`, `--top-percent` and `--min-score` are alternatives; use one of them.

## scores.csv
One row per photo, picked photos first.

| Column | Meaning |
|---|---|
| `rank` | Rank by aesthetic score |
| `picked` | `yes` if copied |
| `note` | `picked` / `not_in_top` / `duplicate` / `below_min_score` / `blurry` / `error` |
| `aesthetic` | LAION aesthetic score, roughly 1–10 (most good photos are around 5–7) |
| `composition_good` | CLIP's probability that the photo is "well composed" (0–1) |
| `scene` | PORTRAIT / LANDSCAPE / NIGHT / ACTION / CLOSEUP / GENERAL |
| `sharpness` | Variance of the Laplacian; higher is sharper |
| `duplicate_of` | For duplicates: the picked photo it resembles |
| `source` / `files` | Scored file / all files of the photo |
| `error` | Why the file could not be read |

## Tips
- Speed: about 10–12 photos per second on a normal PC CPU (800 photos ≈ 70 s).
- **The score is a hint, not a verdict.** It measures general visual appeal and does not know what matters to you. Use it to sort a large set, then choose by eye.
- **Sharpness depends a lot on the subject** (water, fog, night and soft backgrounds score low even when in focus). Before using `--min-sharpness`, look at the `sharpness` column for photos you know are sharp and blurry.
- **Duplicates**: if burst shots are not merged, lower `--similarity` (e.g. 0.92); if different photos are merged, raise it (e.g. 0.96).
- Scores are comparable with the app's, but the app scores the small, compressed liveview, so the same scene scores somewhat lower in the app.

## Tests
```bash
python -m unittest discover tools/photo_picker
```
