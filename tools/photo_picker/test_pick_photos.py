"""Unit tests for the photo picker. Run: python -m unittest discover tools/photo_picker"""
from __future__ import annotations

import io
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pick_photos as pp  # noqa: E402
from arw_preview import extract_preview  # noqa: E402


def jpeg_bytes(size=(64, 48), color=(200, 30, 30)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "JPEG")
    return buf.getvalue()


def fake_arw(path: Path, jpeg: bytes, orientation: int = 1, endian: str = "<") -> None:
    """Minimal TIFF with IFD0 = orientation + JPEG preview offset/length (like Sony ARW)."""
    bom = b"II" if endian == "<" else b"MM"
    entries = [(0x0112, 3, 1, orientation), (0x0201, 4, 1, 0), (0x0202, 4, 1, len(jpeg))]
    ifd_off = 8
    ifd_size = 2 + 12 * len(entries) + 4
    jpeg_off = ifd_off + ifd_size
    out = bom + struct.pack(endian + "HI", 42, ifd_off) + struct.pack(endian + "H", len(entries))
    for tag, typ, count, value in entries:
        if tag == 0x0201:
            value = jpeg_off
        if typ == 3:
            raw = struct.pack(endian + "H", value) + b"\0\0"
        else:
            raw = struct.pack(endian + "I", value)
        out += struct.pack(endian + "HHI", tag, typ, count) + raw
    out += struct.pack(endian + "I", 0) + jpeg
    path.write_bytes(out)


def photo(key: str, score: float, emb, sharp=100.0, error="") -> pp.Photo:
    p = pp.Photo(key, [Path(key + ".jpg")], Path(key + ".jpg"), Path("."))
    p.score, p.sharpness, p.error = score, sharp, error
    v = np.array(emb, dtype=np.float32)
    p.embedding = None if error else v / np.linalg.norm(v)
    return p


class ArwPreviewTest(unittest.TestCase):
    def test_extracts_jpeg_and_orientation_little_and_big_endian(self):
        with tempfile.TemporaryDirectory() as d:
            for endian in "<>":
                p = Path(d) / f"x{endian == '<'}.ARW"
                jp = jpeg_bytes()
                fake_arw(p, jp, orientation=6, endian=endian)
                pv = extract_preview(str(p))
                self.assertEqual(pv.jpeg, jp)
                self.assertEqual(pv.orientation, 6)

    def test_not_tiff_raises(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "bad.ARW"
            p.write_bytes(b"hello world")
            with self.assertRaises(ValueError):
                extract_preview(str(p))

    def test_load_image_rotates_arw_preview_upright(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "r.ARW"
            fake_arw(p, jpeg_bytes(size=(64, 48)), orientation=6)
            img = pp.load_image(p)
            self.assertEqual(img.size, (48, 64))  # landscape preview rotated to portrait


class FindPhotosTest(unittest.TestCase):
    def test_groups_jpg_arw_and_sidecar_prefers_jpg(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for name in ["A.JPG", "A.ARW", "A.xmp", "b.jpg", "c.ARW", "notes.txt", "only.xmp"]:
                (root / name).write_bytes(b"x")
            (root / "sub").mkdir()
            (root / "sub" / "d.jpg").write_bytes(b"x")
            (root / "_picked").mkdir()
            (root / "_picked" / "A.JPG").write_bytes(b"x")

            flat = pp.find_photos(root, recursive=False)
            self.assertEqual([p.key for p in flat], ["a", "b", "c"])
            a = flat[0]
            self.assertEqual(a.source.name, "A.JPG")
            self.assertEqual(sorted(f.name for f in a.files), ["A.ARW", "A.JPG", "A.xmp"])
            self.assertEqual(flat[2].source.name, "c.ARW")

            deep = pp.find_photos(root, recursive=True, exclude=[root / "_picked"])
            keys = [p.key for p in deep]
            self.assertIn(os.path.join("sub", "d"), keys)
            self.assertFalse(any(k.startswith("_picked") for k in keys))


class PickTest(unittest.TestCase):
    def test_top_n_in_score_order(self):
        ps = [photo("a", 5.0, [1, 0, 0]), photo("b", 7.0, [0, 1, 0]), photo("c", 6.0, [0, 0, 1])]
        picks = pp.pick(ps, top=2)
        self.assertEqual([p.key for p in picks], ["b", "c"])
        self.assertEqual(ps[0].note, "not_in_top")

    def test_duplicates_keep_only_the_best_and_do_not_use_quota(self):
        ps = [
            photo("burst1", 7.0, [1, 0.01, 0]),
            photo("burst2", 6.9, [1, 0.02, 0]),  # near-identical to burst1
            photo("other", 5.0, [0, 1, 0]),
        ]
        picks = pp.pick(ps, top=2, similarity=0.94)
        self.assertEqual([p.key for p in picks], ["burst1", "other"])
        self.assertEqual(ps[1].note, "duplicate")
        self.assertEqual(ps[1].duplicate_of, "burst1")

    def test_no_dedupe_allows_similar_photos(self):
        ps = [photo("x", 7.0, [1, 0, 0]), photo("y", 6.9, [1, 0, 0])]
        self.assertEqual(len(pp.pick(ps, top=2, similarity=None)), 2)

    def test_percent_min_score_blur_and_errors(self):
        ps = [photo(str(i), float(i), np.eye(10)[i]) for i in range(10)]
        self.assertEqual([p.key for p in pp.pick(ps, top_percent=20)], ["9", "8"])

        ps = [photo(str(i), float(i), np.eye(10)[i]) for i in range(10)]
        self.assertEqual(len(pp.pick(ps, min_score=7.5)), 2)

        ps = [photo("sharp", 6.0, [1, 0]), photo("blurry", 9.0, [0, 1], sharp=5.0), photo("bad", 0, [1, 1], error="x")]
        picks = pp.pick(ps, min_sharpness=20.0)
        self.assertEqual([p.key for p in picks], ["sharp"])
        self.assertEqual(ps[1].note, "blurry")
        self.assertEqual(ps[2].note, "error")


class ImageTest(unittest.TestCase):
    def test_sharpness_drops_with_blur(self):
        rng = np.random.default_rng(0)
        img = Image.fromarray((rng.random((300, 400, 3)) * 255).astype(np.uint8))
        self.assertGreater(pp.sharpness(img), pp.sharpness(img.filter(ImageFilter.GaussianBlur(4))) * 5)

    def test_jpeg_exif_orientation_is_applied(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "o.jpg"
            exif = Image.Exif()
            exif[0x0112] = 6
            Image.new("RGB", (80, 40), "blue").save(p, exif=exif)
            self.assertEqual(pp.load_image(p).size, (40, 80))


class OutputTest(unittest.TestCase):
    def test_copy_keeps_subfolders_all_files_and_rank_prefix(self):
        with tempfile.TemporaryDirectory() as d:
            src, out = Path(d) / "src", Path(d) / "out"
            (src / "day1").mkdir(parents=True)
            files = [src / "day1" / "A.JPG", src / "day1" / "A.ARW"]
            for f in files:
                f.write_bytes(b"data")
            p = pp.Photo("day1/a", files, files[0], Path("day1"))
            self.assertEqual(pp.copy_picks([p], out, rank_names=True), 2)
            self.assertTrue((out / "day1" / "001_A.JPG").exists())
            self.assertTrue((out / "day1" / "001_A.ARW").exists())
            self.assertTrue(files[0].exists())  # originals untouched

    def test_csv_lists_every_photo_picked_first(self):
        with tempfile.TemporaryDirectory() as d:
            ps = [photo("a", 5.0, [1, 0]), photo("b", 7.0, [0, 1]), photo("c", 0, [1, 1], error="IOError: broken")]
            pp.pick(ps, top=1)
            path = Path(d) / "scores.csv"
            pp.write_csv(ps, path)
            lines = path.read_text(encoding="utf-8-sig").strip().splitlines()
            self.assertEqual(len(lines), 4)
            self.assertTrue(lines[1].startswith("1,yes,picked,7.000"))
            self.assertIn("IOError: broken", lines[3])


class LanguageTest(unittest.TestCase):
    def test_env_override_and_tr(self):
        old = os.environ.get("AIC_LANG")
        try:
            for env, want in [("ja_JP", "ja"), ("zh_CN", "zh"), ("de_DE", "en")]:
                os.environ["AIC_LANG"] = env
                self.assertEqual(pp.detect_lang(), want)
            pp.LANG = "ja"
            self.assertEqual(pp.tr("中", "日", "en"), "日")
        finally:
            pp.LANG = "en"
            if old is None:
                os.environ.pop("AIC_LANG", None)
            else:
                os.environ["AIC_LANG"] = old


@unittest.skipUnless((pp.DEFAULT_MODEL_DIR / "clip_image.onnx").exists(), "model not exported")
class ModelTest(unittest.TestCase):
    def test_scores_are_in_laion_range_and_embeddings_normalized(self):
        m = pp.AestheticModel(pp.DEFAULT_MODEL_DIR)
        rng = np.random.default_rng(1)
        imgs = [Image.fromarray((rng.random((300, 450, 3)) * 255).astype(np.uint8)), Image.new("RGB", (640, 480), "gray")]
        emb = m.embed(imgs)
        self.assertEqual(emb.shape, (2, 512))
        np.testing.assert_allclose(np.linalg.norm(emb, axis=1), 1.0, atol=1e-4)
        aes, comp, scenes = m.score(emb)
        self.assertTrue(np.all((aes > 0) & (aes < 10)))
        self.assertTrue(np.all((comp >= 0) & (comp <= 1)))
        self.assertTrue(all(s in m.scene_names for s in scenes))


if __name__ == "__main__":
    unittest.main()
