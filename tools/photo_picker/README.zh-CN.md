# 电脑端挑片工具

[English](README.md) | **中文** | [日本語](README.ja.md)

用和 AI 构图助手 App 相同的 AI 美学模型（CLIP ViT-B/32 + LAION 美学打分头）给一整个文件夹的照片打分，挑出最好的，复制到另一个文件夹。原照片不会被修改。

- 支持 JPG / PNG / TIFF / WebP 和**索尼 ARW**（读取 ARW 里内嵌的 JPEG 预览，不需要额外安装库）
- 同名的 JPG 和 ARW（`DSC01234.JPG` + `DSC01234.ARW`）算一张照片：只打一次分，入选时两个文件一起复制（`.xmp` 附属文件也一起）
- **去掉相似照片**：连拍、同一个场景拍了好几张时，只保留最好的一张
- 生成 `scores.csv`，记录每张照片的分数（Excel 可以直接打开）
- 提示语言跟随系统（中文 / 日语 / 英文）

## 准备（一次性）
1. Python 3.10 或更新版本。
2. 安装需要的库：
   ```bash
   pip install -r tools/photo_picker/requirements.txt
   ```
3. 生成模型（如果之前为 App 生成过就不用再做）：
   ```bash
   pip install torch open_clip_torch onnx onnxruntime
   python tools/export_clip_model.py
   ```
   会生成 `core/src/main/assets/clip_image.onnx` 和 `clip_meta.json`，挑片工具默认就用这两个文件。

## 使用
```bash
# 挑出最好的 20%（默认），复制到 D:/Photo/2026-11-17/_picked
python tools/photo_picker/pick_photos.py D:/Photo/2026-11-17

# 挑最好的 30 张，包括子文件夹，文件名前加名次（001_DSC01234.JPG）
python tools/photo_picker/pick_photos.py D:/Photo/trip --top 30 --recursive --rank-names

# 美学分 6.0 以上的全部挑出来，放到另一个文件夹
python tools/photo_picker/pick_photos.py D:/Photo/trip --min-score 6.0 -o D:/Photo/trip_best

# 只打分不复制，先看看 scores.csv
python tools/photo_picker/pick_photos.py D:/Photo/trip --dry-run
```

| 选项 | 含义 |
|---|---|
| `--top N` | 挑分数最高的 N 张 |
| `--top-percent P` | 挑分数最高的 P%（默认 20） |
| `--min-score S` | 挑美学分 ≥ S 的全部照片 |
| `-o 文件夹` | 输出文件夹（默认：照片文件夹里的 `_picked`） |
| `-r`, `--recursive` | 也处理子文件夹（输出时保留子文件夹结构） |
| `--similarity X` | 相似度 ≥ X 算重复（默认 0.94；越低，越多照片会被当成重复） |
| `--no-dedupe` | 不去重，相似照片也可以都入选 |
| `--min-sharpness X` | 清晰度低于 X 的不入选（默认 0 = 不过滤） |
| `--rank-names` | 复制时在文件名前加名次 |
| `--dry-run` | 只打分和生成 `scores.csv` |
| `--lang zh/ja/en` | 提示语言 |
| `--model-dir 文件夹` | 放 `clip_image.onnx` + `clip_meta.json` 的文件夹 |

`--top`、`--top-percent`、`--min-score` 三选一。

## scores.csv
每张照片一行，入选的排在前面。

| 列 | 含义 |
|---|---|
| `rank` | 按美学分的名次 |
| `picked` | 复制了的是 `yes` |
| `note` | `picked`（入选）/ `not_in_top`（没进前几名）/ `duplicate`（重复）/ `below_min_score`（低于分数线）/ `blurry`（太模糊）/ `error`（读取失败） |
| `aesthetic` | LAION 美学分，大约 1~10（大部分好照片在 5~7 左右） |
| `composition_good` | CLIP 认为「构图好」的概率（0~1） |
| `scene` | PORTRAIT（人像）/ LANDSCAPE（风景）/ NIGHT（夜景）/ ACTION（动体）/ CLOSEUP（特写）/ GENERAL（日常） |
| `sharpness` | 拉普拉斯方差，越大越清晰 |
| `duplicate_of` | 重复照片：和哪张入选照片相似 |
| `source` / `files` | 用来打分的文件 / 这张照片的所有文件 |
| `error` | 读取失败的原因 |

## 小贴士
- 速度：普通电脑 CPU 每秒约 10~12 张（800 张约 70 秒）。
- **分数只是参考，不是结论。** 它衡量的是一般意义上的「好看」，不知道哪张对你有意义。适合先把一大堆照片排个序，最后还是自己用眼睛挑。
- **清晰度和题材关系很大**（水、雾、夜景、虚化背景即使对上焦，数值也低）。用 `--min-sharpness` 之前，先在 `sharpness` 列里看看你知道清楚和模糊的照片分别是多少。
- **去重**：连拍没有被合并时，把 `--similarity` 调低（比如 0.92）；不同的照片被当成重复时，调高（比如 0.96）。
- 分数和 App 里的可以对照，但 App 打分用的是又小又压缩的取景画面，同一个场景在 App 里的分数会低一些。

## 测试
```bash
python -m unittest discover tools/photo_picker
```
