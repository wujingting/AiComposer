"""
Score photos with the same CLIP + LAION aesthetic model as the AI Composer app, then pick the best ones.

    python tools/photo_picker/pick_photos.py D:/Photo/2026-11-17
    python tools/photo_picker/pick_photos.py D:/Photo/trip --top 30 --recursive
    python tools/photo_picker/pick_photos.py D:/Photo/trip --min-score 5.5 --dry-run

What it does:
  1. Finds photos (JPG/PNG/TIFF/WebP and Sony ARW). A JPG and an ARW with the same name are one photo:
     it is scored once (from the JPG when there is one) and both files are copied when picked.
  2. Scores each photo: LAION aesthetic score (about 1-10), CLIP "well composed" probability, scene, sharpness.
  3. Picks the best ones (top 20% by default). Near-duplicates (burst shots, the same scene shot twice)
     are detected with CLIP embeddings and only the best of each is kept.
  4. Copies the picks to an output folder (originals are never modified) and writes scores.csv for all photos.

Model files: core/src/main/assets/clip_image.onnx + clip_meta.json (generate them with tools/export_clip_model.py).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import locale
import os
import shutil
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Optional

import numpy as np
from PIL import Image, ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parent))
from arw_preview import extract_preview  # noqa: E402

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp"}
RAW_EXTS = {".arw"}
SIDECAR_EXTS = {".xmp"}
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODEL_DIR = REPO_ROOT / "core" / "src" / "main" / "assets"
DEFAULT_OUT_NAME = "_picked"

# ---------------------------------------------------------------------------
# Language (follows the system like the app: Chinese, Japanese, otherwise English)
# ---------------------------------------------------------------------------
LANG = "en"


def detect_lang() -> str:
    env = os.environ.get("AIC_LANG") or os.environ.get("LANG") or ""
    candidates = [env]
    if sys.platform == "win32":
        try:
            import ctypes

            primary = ctypes.windll.kernel32.GetUserDefaultUILanguage() & 0x3FF
            candidates.append({0x04: "zh", 0x11: "ja"}.get(primary, "en"))
        except Exception:
            pass
    try:
        candidates.append(locale.getlocale()[0] or "")
    except Exception:
        pass
    for c in candidates:
        c = (c or "").lower()
        if c.startswith("zh") or c.startswith("chinese"):
            return "zh"
        if c.startswith("ja") or c.startswith("japanese"):
            return "ja"
        if c:
            return "en"
    return "en"


def tr(zh: str, ja: str, en: str) -> str:
    return {"zh": zh, "ja": ja}.get(LANG, en)


def say(msg: str) -> None:
    print(msg, flush=True)


# ---------------------------------------------------------------------------
# Finding photos
# ---------------------------------------------------------------------------
@dataclass
class Photo:
    key: str                     # relative path without extension, lower-case (groups JPG + ARW)
    files: list[Path]            # every file belonging to this photo (JPG, ARW, XMP...)
    source: Path                 # file used for scoring
    rel_dir: Path                # folder relative to the input root (kept in the output)
    score: float = float("nan")  # LAION aesthetic score, about 1..10
    composition: float = float("nan")  # CLIP "well composed" probability 0..1
    scene: str = ""
    sharpness: float = float("nan")
    embedding: Optional[np.ndarray] = None
    error: str = ""
    picked: bool = False
    note: str = ""               # why it was (not) picked
    duplicate_of: str = ""


def find_photos(root: Path, recursive: bool, exclude: Iterable[Path] = ()) -> list[Photo]:
    """Group files by folder + name; pick the scoring source (JPEG-like first, then raw)."""
    exclude = [e.resolve() for e in exclude]
    groups: dict[str, list[Path]] = {}
    walker = root.rglob("*") if recursive else root.glob("*")
    for p in walker:
        if not p.is_file():
            continue
        rp = p.resolve()
        if any(rp == e or e in rp.parents for e in exclude):
            continue
        ext = p.suffix.lower()
        if ext not in IMAGE_EXTS | RAW_EXTS | SIDECAR_EXTS:
            continue
        key = str(p.relative_to(root).with_suffix("")).lower()
        groups.setdefault(key, []).append(p)

    photos = []
    for key, files in groups.items():
        images = [f for f in files if f.suffix.lower() in IMAGE_EXTS]
        raws = [f for f in files if f.suffix.lower() in RAW_EXTS]
        source = (sorted(images) or sorted(raws) or [None])[0]
        if source is None:
            continue  # only a sidecar
        photos.append(Photo(key, sorted(files), source, source.parent.relative_to(root)))
    photos.sort(key=lambda ph: ph.key)
    return photos


# ---------------------------------------------------------------------------
# Loading images
# ---------------------------------------------------------------------------
# TIFF orientation -> PIL transpose
_ORIENT = {
    2: [Image.Transpose.FLIP_LEFT_RIGHT],
    3: [Image.Transpose.ROTATE_180],
    4: [Image.Transpose.FLIP_TOP_BOTTOM],
    5: [Image.Transpose.TRANSPOSE],
    6: [Image.Transpose.ROTATE_270],
    7: [Image.Transpose.TRANSVERSE],
    8: [Image.Transpose.ROTATE_90],
}

WORK_SIZE = 1024  # decode size: plenty for CLIP (224px) and for a coarse sharpness check


def apply_orientation(img: Image.Image, orientation: int) -> Image.Image:
    for t in _ORIENT.get(orientation, []):
        img = img.transpose(t)
    return img


def load_image(path: Path) -> Image.Image:
    """Decode to RGB at about WORK_SIZE on the long side, upright."""
    if path.suffix.lower() in RAW_EXTS:
        pv = extract_preview(str(path))
        if pv is not None:
            img = Image.open(io.BytesIO(pv.jpeg))
            img.draft("RGB", (WORK_SIZE, WORK_SIZE))
            img = apply_orientation(img.convert("RGB"), pv.orientation)
        else:
            img = _load_raw_with_rawpy(path)
    else:
        img = Image.open(path)
        img.draft("RGB", (WORK_SIZE, WORK_SIZE))  # fast JPEG DCT downscaling
        img = ImageOps.exif_transpose(img).convert("RGB")
    img.thumbnail((WORK_SIZE * 2, WORK_SIZE * 2))
    return img


def _load_raw_with_rawpy(path: Path) -> Image.Image:
    try:
        import rawpy  # optional
    except ImportError as e:
        raise RuntimeError(tr("ARW 里没有内嵌预览，需要安装 rawpy（pip install rawpy）",
                              "ARW に埋め込みプレビューがありません。rawpy が必要です（pip install rawpy）",
                              "No embedded preview in the ARW; install rawpy (pip install rawpy)")) from e
    with rawpy.imread(str(path)) as raw:
        rgb = raw.postprocess(half_size=True, use_camera_wb=True)
    return Image.fromarray(rgb)


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------
def sharpness(img: Image.Image) -> float:
    """Variance of the Laplacian on a 1024px grayscale image. Higher = sharper. Only catches obvious blur."""
    g = img.convert("L")
    g.thumbnail((WORK_SIZE, WORK_SIZE))
    a = np.asarray(g, dtype=np.float32)
    if a.shape[0] < 3 or a.shape[1] < 3:
        return 0.0
    lap = a[1:-1, :-2] + a[1:-1, 2:] + a[:-2, 1:-1] + a[2:, 1:-1] - 4 * a[1:-1, 1:-1]
    return float(lap.var())


class AestheticModel:
    """Same model and math as AestheticModel.kt in the app."""

    def __init__(self, model_dir: Path):
        import onnxruntime as ort

        onnx_path = model_dir / "clip_image.onnx"
        meta_path = model_dir / "clip_meta.json"
        if not onnx_path.exists() or not meta_path.exists():
            raise FileNotFoundError(tr(
                f"找不到模型文件：{onnx_path}\n请先运行 python tools/export_clip_model.py",
                f"モデルファイルが見つかりません：{onnx_path}\n先に python tools/export_clip_model.py を実行してください",
                f"Model files not found: {onnx_path}\nRun python tools/export_clip_model.py first",
            ))
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        self.size = int(meta.get("image_size", 224))
        self.mean = np.array(meta["mean"], dtype=np.float32).reshape(3, 1, 1)
        self.std = np.array(meta["std"], dtype=np.float32).reshape(3, 1, 1)
        self.logit_scale = float(meta.get("logit_scale", 100.0))
        self.head_w = np.array(meta["head"]["w"], dtype=np.float32)
        self.head_b = float(meta["head"]["b"])
        self.scene_names = list(meta["scenes"].keys())
        self.scenes = np.array([meta["scenes"][k] for k in self.scene_names], dtype=np.float32)
        self.comp = np.array([meta["composition"]["good"], meta["composition"]["bad"]], dtype=np.float32)

        opts = ort.SessionOptions()
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(str(onnx_path), opts, providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name

    def preprocess(self, img: Image.Image) -> np.ndarray:
        """CLIP preprocessing: short side to 224 (bicubic) -> center crop -> normalize, CHW."""
        s = self.size
        w, h = img.size
        scale = s / min(w, h)
        sw, sh = max(s, round(w * scale)), max(s, round(h * scale))
        img = img.resize((sw, sh), Image.Resampling.BICUBIC)
        left, top = (sw - s) // 2, (sh - s) // 2
        img = img.crop((left, top, left + s, top + s))
        a = np.asarray(img, dtype=np.float32).transpose(2, 0, 1) / 255.0
        return (a - self.mean) / self.std

    def embed(self, images: list[Image.Image]) -> np.ndarray:
        x = np.stack([self.preprocess(i) for i in images]).astype(np.float32)
        out = self.session.run(None, {self.input_name: x})[0]
        return out / np.linalg.norm(out, axis=1, keepdims=True)

    def score(self, emb: np.ndarray) -> tuple[np.ndarray, np.ndarray, list[str]]:
        """(aesthetic scores, "well composed" probabilities, scene names) for L2-normalized embeddings."""
        aes = emb @ self.head_w + self.head_b
        comp = _softmax(self.logit_scale * emb @ self.comp.T)[:, 0]
        scene_idx = (self.logit_scale * emb @ self.scenes.T).argmax(axis=1)
        return aes, comp, [self.scene_names[i] for i in scene_idx]


def _softmax(x: np.ndarray) -> np.ndarray:
    e = np.exp(x - x.max(axis=1, keepdims=True))
    return e / e.sum(axis=1, keepdims=True)


def score_all(photos: list[Photo], model: AestheticModel, batch: int = 16, workers: int = 4) -> None:
    def load(ph: Photo):
        try:
            img = load_image(ph.source)
            return img, sharpness(img)
        except Exception as e:  # unreadable file: keep going, report it in the CSV
            ph.error = f"{type(e).__name__}: {e}"
            return None, None

    t0 = time.time()
    done = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for start in range(0, len(photos), batch):
            chunk = photos[start:start + batch]
            loaded = list(pool.map(load, chunk))
            ok = [(ph, img, sh) for ph, (img, sh) in zip(chunk, loaded) if img is not None]
            if ok:
                emb = model.embed([img for _, img, _ in ok])
                aes, comp, scenes = model.score(emb)
                for i, (ph, _, sh) in enumerate(ok):
                    ph.embedding = emb[i]
                    ph.score, ph.composition, ph.scene, ph.sharpness = float(aes[i]), float(comp[i]), scenes[i], sh
            done += len(chunk)
            rate = done / max(time.time() - t0, 1e-6)
            say(tr(f"  已打分 {done}/{len(photos)}（{rate:.1f} 张/秒）",
                   f"  採点済み {done}/{len(photos)}（{rate:.1f} 枚/秒）",
                   f"  scored {done}/{len(photos)} ({rate:.1f} photos/s)"))


# ---------------------------------------------------------------------------
# Picking
# ---------------------------------------------------------------------------
def pick(
    photos: list[Photo],
    top: Optional[int] = None,
    top_percent: Optional[float] = None,
    min_score: Optional[float] = None,
    similarity: Optional[float] = 0.94,
    min_sharpness: float = 0.0,
) -> list[Photo]:
    """
    Greedy pick in score order. A photo is skipped when:
      - it could not be read, or is below --min-sharpness / --min-score
      - it is a near-duplicate (CLIP cosine similarity >= similarity) of a photo already picked
    Stops when the quota (--top / --top-percent) is reached. Returns the picks in rank order.
    """
    scored = [p for p in photos if not p.error and p.embedding is not None]
    for p in photos:
        if p.error:
            p.note = "error"
    if top is not None:
        quota = top
    elif top_percent is not None:
        quota = max(1, round(len(scored) * top_percent / 100)) if scored else 0
    else:
        quota = len(scored)

    picks: list[Photo] = []
    for p in sorted(scored, key=lambda x: -x.score):
        if min_score is not None and p.score < min_score:
            p.note = "below_min_score"
            continue
        if p.sharpness < min_sharpness:
            p.note = "blurry"
            continue
        if similarity is not None:
            dup = next((q for q in picks if float(p.embedding @ q.embedding) >= similarity), None)
            if dup is not None:
                p.note, p.duplicate_of = "duplicate", dup.key
                continue
        if len(picks) >= quota:
            p.note = "not_in_top"
            continue
        p.picked, p.note = True, "picked"
        picks.append(p)
    return picks


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
def copy_picks(picks: list[Photo], out: Path, rank_names: bool) -> int:
    n = 0
    for rank, p in enumerate(picks, 1):
        dest_dir = out / p.rel_dir
        dest_dir.mkdir(parents=True, exist_ok=True)
        for f in p.files:
            name = f"{rank:03d}_{f.name}" if rank_names else f.name
            shutil.copy2(f, dest_dir / name)
            n += 1
    return n


def write_csv(photos: list[Photo], path: Path) -> None:
    order = sorted(photos, key=lambda p: (not p.picked, -(p.score if p.score == p.score else -1e9)))
    ranks = {id(p): i for i, p in enumerate(sorted([p for p in photos if not p.error], key=lambda p: -p.score), 1)}
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8-sig") as f:  # utf-8-sig: opens correctly in Excel
        w = csv.writer(f)
        w.writerow(["rank", "picked", "note", "aesthetic", "composition_good", "scene", "sharpness",
                    "duplicate_of", "source", "files", "error"])
        for p in order:
            w.writerow([
                ranks.get(id(p), ""), "yes" if p.picked else "", p.note,
                "" if p.error else f"{p.score:.3f}", "" if p.error else f"{p.composition:.3f}",
                p.scene, "" if p.error else f"{p.sharpness:.1f}", p.duplicate_of,
                str(p.source), " | ".join(f.name for f in p.files), p.error,
            ])


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description=tr("用 AI 美学模型给照片打分并自动挑片（支持 JPG 和索尼 ARW）",
                       "AI 美的評価モデルで写真を採点し、自動でセレクト（JPG とソニー ARW に対応）",
                       "Score photos with the AI aesthetic model and pick the best (JPG and Sony ARW)"),
    )
    ap.add_argument("input", type=Path, help=tr("照片文件夹", "写真フォルダー", "photo folder"))
    ap.add_argument("-o", "--out", type=Path,
                    help=tr(f"输出文件夹（默认：<照片文件夹>/{DEFAULT_OUT_NAME}）",
                            f"出力フォルダー（デフォルト：<写真フォルダー>/{DEFAULT_OUT_NAME}）",
                            f"output folder (default: <photo folder>/{DEFAULT_OUT_NAME})"))
    sel = ap.add_mutually_exclusive_group()
    sel.add_argument("--top", type=int, help=tr("挑分数最高的 N 张", "スコア上位 N 枚を選ぶ", "pick the N best photos"))
    sel.add_argument("--top-percent", type=float,
                     help=tr("挑分数最高的百分之几（默认 20）", "スコア上位何 % を選ぶか（デフォルト 20）",
                             "pick the best N percent (default 20)"))
    sel.add_argument("--min-score", type=float,
                     help=tr("挑美学分 ≥ 这个值的全部照片（LAION 分，约 1~10）",
                             "美的評価がこの値以上の写真をすべて選ぶ（LAION スコア、約 1〜10）",
                             "pick every photo with an aesthetic score >= this (LAION scale, about 1-10)"))
    ap.add_argument("--similarity", type=float, default=0.94,
                    help=tr("相似度 ≥ 这个值算重复（连拍），只保留最好的一张（默认 0.94）",
                            "類似度がこの値以上なら重複（連写）とみなし、最良の 1 枚だけ残す（デフォルト 0.94）",
                            "photos this similar or more count as duplicates (bursts); keep only the best (default 0.94)"))
    ap.add_argument("--no-dedupe", action="store_true",
                    help=tr("不去重，相似照片也都可以入选", "重複を除外しない", "do not remove near-duplicates"))
    ap.add_argument("--min-sharpness", type=float, default=0.0,
                    help=tr("清晰度低于这个值的照片不入选（默认 0 = 不过滤；参考 scores.csv 里的 sharpness 列）",
                            "シャープネスがこの値未満の写真は選ばない（デフォルト 0 = 無効。scores.csv の sharpness 列を参考に）",
                            "skip photos with sharpness below this (default 0 = off; see the sharpness column in scores.csv)"))
    ap.add_argument("-r", "--recursive", action="store_true",
                    help=tr("也处理子文件夹", "サブフォルダーも処理する", "include subfolders"))
    ap.add_argument("--rank-names", action="store_true",
                    help=tr("复制时在文件名前加名次（001_DSC01234.JPG）",
                            "コピー時にファイル名の先頭に順位を付ける（001_DSC01234.JPG）",
                            "prefix copied files with their rank (001_DSC01234.JPG)"))
    ap.add_argument("--dry-run", action="store_true",
                    help=tr("只打分和生成 scores.csv，不复制照片", "採点と scores.csv の作成だけ行い、コピーしない",
                            "score and write scores.csv only, do not copy photos"))
    ap.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR,
                    help=tr("模型文件夹（clip_image.onnx + clip_meta.json）",
                            "モデルフォルダー（clip_image.onnx + clip_meta.json）",
                            "model folder (clip_image.onnx + clip_meta.json)"))
    ap.add_argument("--lang", choices=["zh", "ja", "en"], help=tr("提示语言", "表示言語", "message language"))
    return ap


def main(argv: Optional[list[str]] = None) -> int:
    global LANG
    LANG = detect_lang()
    if argv is None:
        argv = sys.argv[1:]
    if "--lang" in argv:  # set early so --help is translated too
        i = argv.index("--lang")
        if i + 1 < len(argv) and argv[i + 1] in ("zh", "ja", "en"):
            LANG = argv[i + 1]
    try:
        # A real Windows console handles Unicode itself; when redirected, write UTF-8 instead of the ANSI code page
        if sys.stdout.isatty():
            sys.stdout.reconfigure(errors="replace")
        else:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    args = build_parser().parse_args(argv)
    root: Path = args.input
    if not root.is_dir():
        say(tr(f"找不到文件夹：{root}", f"フォルダーが見つかりません：{root}", f"Folder not found: {root}"))
        return 2
    out: Path = args.out or (root / DEFAULT_OUT_NAME)
    if args.top is None and args.top_percent is None and args.min_score is None:
        args.top_percent = 20.0

    photos = find_photos(root, args.recursive, exclude=[out])
    raw_pairs = sum(1 for p in photos if len(p.files) > 1)
    say(tr(f"找到 {len(photos)} 张照片（其中 {raw_pairs} 张有 JPG+ARW 等多个文件）",
           f"{len(photos)} 枚の写真が見つかりました（うち {raw_pairs} 枚は JPG+ARW など複数ファイル）",
           f"Found {len(photos)} photos ({raw_pairs} with several files such as JPG + ARW)"))
    if not photos:
        return 1

    say(tr("加载模型…", "モデルを読み込み中…", "Loading the model…"))
    try:
        model = AestheticModel(args.model_dir)
    except FileNotFoundError as e:
        say(str(e))
        return 2

    score_all(photos, model)
    picks = pick(
        photos, top=args.top, top_percent=args.top_percent, min_score=args.min_score,
        similarity=None if args.no_dedupe else args.similarity, min_sharpness=args.min_sharpness,
    )

    csv_path = out / "scores.csv"
    write_csv(photos, csv_path)
    errors = [p for p in photos if p.error]
    dups = sum(1 for p in photos if p.note == "duplicate")

    if args.dry_run:
        copied = 0
    else:
        copied = copy_picks(picks, out, args.rank_names)

    say("")
    say(tr(f"入选 {len(picks)} 张（去掉了 {dups} 张相似照片），复制了 {copied} 个文件到 {out}",
           f"{len(picks)} 枚を選びました（類似写真 {dups} 枚を除外）。{copied} ファイルを {out} にコピーしました",
           f"Picked {len(picks)} photos ({dups} near-duplicates skipped), copied {copied} files to {out}"))
    if args.dry_run:
        say(tr("（--dry-run：没有复制照片）", "（--dry-run：写真はコピーしていません）", "(--dry-run: no photos copied)"))
    say(tr(f"全部照片的分数：{csv_path}", f"全写真のスコア：{csv_path}", f"Scores for all photos: {csv_path}"))
    for rank, p in enumerate(picks[:10], 1):
        say(f"  {rank:3d}. {p.score:5.2f}  {p.source.relative_to(root)}")
    if len(picks) > 10:
        say("  …")
    if errors:
        say(tr(f"{len(errors)} 张无法读取，原因见 scores.csv 的 error 列",
               f"{len(errors)} 枚を読み込めませんでした。理由は scores.csv の error 列を参照",
               f"{len(errors)} photos could not be read; see the error column in scores.csv"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
