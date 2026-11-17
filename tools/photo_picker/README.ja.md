# PC 用セレクトツール

[English](README.md) | [中文](README.zh-CN.md) | **日本語**

AI 構図アシスタントのアプリと同じ AI 美的評価モデル（CLIP ViT-B/32 + LAION 美的評価ヘッド）で、フォルダー内の写真をまとめて採点し、良いものを選んで別のフォルダーにコピーします。元の写真は変更しません。

- JPG / PNG / TIFF / WebP と**ソニー ARW** に対応（ARW に埋め込まれた JPEG プレビューを読むので、追加のライブラリは不要）
- 同じ名前の JPG と ARW（`DSC01234.JPG` + `DSC01234.ARW`）は 1 枚として扱います：採点は 1 回だけで、選ばれたら両方をコピー（`.xmp` サイドカーも一緒に）
- **類似写真の除外**：連写や同じシーンを何枚も撮った場合、一番良い 1 枚だけを残します
- 全写真のスコアを記録した `scores.csv` を作成（Excel でそのまま開けます）
- メッセージはシステムの言語に従います（中国語／日本語／英語）

## 準備（1 回だけ）
1. Python 3.10 以上。
2. ライブラリをインストール：
   ```bash
   pip install -r tools/photo_picker/requirements.txt
   ```
3. モデルを生成（アプリ用にすでに生成済みなら不要）：
   ```bash
   pip install torch open_clip_torch onnx onnxruntime
   python tools/export_clip_model.py
   ```
   `core/src/main/assets/clip_image.onnx` と `clip_meta.json` が作られ、セレクトツールはデフォルトでこれを使います。

## 使い方
```bash
# 上位 20%（デフォルト）を選び、D:/Photo/2026-11-17/_picked にコピー
python tools/photo_picker/pick_photos.py D:/Photo/2026-11-17

# サブフォルダーも含めて上位 30 枚を選び、ファイル名の先頭に順位を付ける（001_DSC01234.JPG）
python tools/photo_picker/pick_photos.py D:/Photo/trip --top 30 --recursive --rank-names

# 美的評価 6.0 以上の写真をすべて別のフォルダーへ
python tools/photo_picker/pick_photos.py D:/Photo/trip --min-score 6.0 -o D:/Photo/trip_best

# コピーせずに採点だけ行い、まず scores.csv を確認
python tools/photo_picker/pick_photos.py D:/Photo/trip --dry-run
```

| オプション | 意味 |
|---|---|
| `--top N` | スコア上位 N 枚を選ぶ |
| `--top-percent P` | スコア上位 P% を選ぶ（デフォルト 20） |
| `--min-score S` | 美的評価が S 以上の写真をすべて選ぶ |
| `-o フォルダー` | 出力フォルダー（デフォルト：写真フォルダー内の `_picked`） |
| `-r`, `--recursive` | サブフォルダーも処理（出力でもフォルダー構成を保持） |
| `--similarity X` | 類似度が X 以上なら重複とみなす（デフォルト 0.94。低いほど多くの写真が重複扱いになる） |
| `--no-dedupe` | 重複を除外しない |
| `--min-sharpness X` | シャープネスが X 未満の写真は選ばない（デフォルト 0 = 無効） |
| `--rank-names` | コピー時にファイル名の先頭に順位を付ける |
| `--dry-run` | 採点と `scores.csv` の作成だけ行う |
| `--lang zh/ja/en` | メッセージの言語 |
| `--model-dir フォルダー` | `clip_image.onnx` + `clip_meta.json` のあるフォルダー |

`--top`、`--top-percent`、`--min-score` はどれか 1 つを使います。

## scores.csv
写真ごとに 1 行。選ばれた写真が先頭に並びます。

| 列 | 意味 |
|---|---|
| `rank` | 美的評価による順位 |
| `picked` | コピーされた写真は `yes` |
| `note` | `picked`（選択）／`not_in_top`（上位に入らず）／`duplicate`（重複）／`below_min_score`（基準点未満）／`blurry`（ブレ・ボケ）／`error`（読み込み失敗） |
| `aesthetic` | LAION 美的評価、おおむね 1〜10（良い写真の多くは 5〜7 前後） |
| `composition_good` | CLIP が「構図が良い」と判断する確率（0〜1） |
| `scene` | PORTRAIT（ポートレート）／LANDSCAPE（風景）／NIGHT（夜景）／ACTION（動体）／CLOSEUP（クローズアップ）／GENERAL（スナップ） |
| `sharpness` | ラプラシアンの分散。大きいほどシャープ |
| `duplicate_of` | 重複写真の場合：似ている選択済みの写真 |
| `source` / `files` | 採点に使ったファイル／その写真のすべてのファイル |
| `error` | 読み込めなかった理由 |

## ヒント
- 速度：一般的な PC の CPU で毎秒 10〜12 枚程度（800 枚で約 70 秒）。
- **スコアは目安であり、結論ではありません。** 一般的な見た目の良さを測るもので、あなたにとって大切な写真かどうかは分かりません。大量の写真を並べ替えるのに使い、最後は自分の目で選んでください。
- **シャープネスは題材による差が大きい**です（水、霧、夜景、背景ボケはピントが合っていても低く出ます）。`--min-sharpness` を使う前に、シャープだと分かっている写真とブレた写真の `sharpness` 列の値を確認してください。
- **重複**：連写がまとめられない場合は `--similarity` を下げ（例：0.92）、別の写真がまとめられてしまう場合は上げて（例：0.96）ください。
- アプリのスコアと比較できますが、アプリは小さく圧縮されたライブビューで採点するため、同じシーンでもアプリでは少し低めに出ます。

## テスト
```bash
python -m unittest discover tools/photo_picker
```
