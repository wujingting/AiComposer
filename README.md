# AI Composer

**English** | [中文](README.zh-CN.md) | [日本語](README.ja.md)

An on-device photography coach: it looks at the frame, explains what is wrong with the composition, light and focus and how to fix it, and scores the image with an AI aesthetic model (CLIP + LAION). Everything runs offline on the phone or computer.

| Product | What it is | For |
|---|---|---|
| **AI Composer** (`app-sony`) | Android app that connects to a Sony a6000 over Wi-Fi: live coaching, exposure advice, one-tap apply, auto shutter | Shooting with the Sony camera |
| **AI Composer Phone** (`app-phone`) | Android app with the same live coaching on the phone's own camera | Shooting with the phone |
| **AI Photo Score** (`app-score`) | Android app that scores photos you already took, marks the best and near-duplicates, explains each photo and suggests a crop | Choosing and editing photos on the phone |
| **PC photo picker** (`tools/photo_picker`) | Python tool that scores a whole folder (JPG + Sony ARW), removes burst duplicates and copies the best | Culling a shoot on the computer |

All three apps share one library (`core`): the same rules, AI model and UI pieces, so a fix there reaches every app. The PC picker uses the same AI model and scoring.

The UI language follows the system language: Chinese, Japanese, and English for everything else. The apps work in both landscape and portrait, following the system auto-rotate setting.

The sections below describe the Sony camera version in detail first (most of it — the coaching engine, AI model and UI — applies to the phone version too), then the other products.

## Features (Sony camera version)
| Feature | Description |
|---|---|
| Live view | Liveview stream from the Sony Camera Remote API |
| Thirds grid + composition score | Distance from the subject to the nearest thirds point, touching edges, headroom, horizon; combined into a 0–100 score |
| Subject detection | ML Kit face + object detection (offline). Yellow box = face, cyan box = object, thick box = main subject |
| Composition hints | "Put the person on a thirds point: pan right and tilt down" |
| Spirit level | Finds the horizon in the frame and shows its tilt |
| Scene detection | Portrait / landscape / night / action / close-up / everyday snapshot |
| Exposure advice | Aperture, shutter, ISO, exposure compensation, with reasons (backlight, clipping, shake, motion) |
| One-tap apply | Writes the suggested settings to the camera (limited by the mode dial, see below) |
| **AI aesthetic score** | CLIP ViT-B/32 + LAION aesthetic head, about once per second |
| **AI scene detection** | Zero-shot classification on the same CLIP embedding, more reliable than the rules (action and low light still use rules) |
| **AI framing** | Searches 19 candidate crops in the frame, marks the best-scoring one with a purple dashed frame, and tells you how to move |
| **Planned crop** | When the lens is not long enough, set how much you will crop later and the final aspect ratio; composition is scored inside the white frame (expected final image), and the safe shutter speed and ISO limit are tightened automatically |
| Auto shutter | Fires when the combined score (composition rules + aesthetics) stays above the threshold for about 0.75 s, at least 4 s apart |
| **Direction arrows** | Big yellow arrows on the edge of the viewfinder show which way to turn the camera; one short vibration when the framing falls into place (at most once every 4 s) |

## UI
- **Landscape**: viewfinder on the left, panel on the right. **Portrait**: viewfinder on top, panel below.
- The panel, from top to bottom:
  1. **Hint strip**: big overall score (with the scene) + the 3 most important problems as short phrases (e.g. "Person cut off at left"), each with a severity bar. Fixed height, so the buttons below never jump. The full advice is in the checklist.
  2. **Guide tab**: checklist, suggested settings, current camera settings, AI aesthetics. These change in real time.
  3. **Settings tab**: auto-shutter threshold, lens focal length, planned crop, ignored rules.
  4. **Bottom action bar** (fixed position): Shoot / Apply / AI framing, auto-shutter switch, Connect / Disconnect.
- Only graphics are drawn on the viewfinder (thirds grid, detection boxes, spirit level, direction arrows...), so text never covers the image.
- Built for outdoor use: large text, neutral grey surround (does not bias how you judge the photo's colors), and one signal color: **yellow = what to do** (main button, current choice, arrows); green / orange / red = good / warning / problem. All three apps share these colors and the type scale (`core/ui/Theme.kt`).

## Build and run (first time)
1. Install **Android Studio** (Windows or Mac) and let the setup wizard install the SDK.
2. File → Open → select the `AiComposer` folder → wait for the Gradle sync in the bottom right to finish (the first time downloads dependencies, 10–30 minutes, needs internet).
   - If asked to "Upgrade Android Gradle Plugin", **decline for now**.
   - If SDK 35 is missing, click the link in the message to install it.
   - **Set Gradle JDK to JDK 21** (Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK). Recent Android Studio versions bundle JDK 25, which Gradle 8.11 does not support.
3. Build → **Make Project**, and check the Build window at the bottom for red errors.
4. On the phone, enable "Developer options → USB debugging", connect it with a USB cable, select it at the top, pick the app in the run configuration menu next to ▶ (**app-sony** = Sony camera version, **app-phone** = phone camera version, **app-score** = photo scoring app), and click ▶ Run.
5. Unit tests: `gradlew.bat test` on the command line (`./gradlew test` on Mac), or in Android Studio right-click `core/src/test` → Run Tests (run them with Gradle).

### When the build fails
Copy the **first red error** in the Build window (the line with the file name and line number, plus the lines below it).
Fixing one error often makes a whole chain of errors disappear, so only the first few matter each time.

## Coaching engine (rule set)
About 45 rules in `coach/`, in several categories. Each one says "what is wrong + how to fix it":

| Category | Rules |
|---|---|
| Composition | Subject position (thirds points / detects symmetric and centered compositions), subject cut off, subject size, look room and lead room, head merger, busy background, subject separation, edge distractions, balance, level horizon |
| Light / exposure | Too dark / too bright overall (tells you which dial to turn in order: shutter → aperture → ISO, respecting the safe shutter speed and ISO limit), backlight, clipping, overexposed face, too much contrast (DRO/HDR), hard / overhead light, white balance and mixed light, snow and beaches, low light |
| Focus / settings | Shutter fast enough, ISO noise, depth of field (distance estimated from face size; front-to-back depth for group shots), focus on the subject, camera shake, focus mode, pixels left after cropping |
| Portrait | Closed eyes, expression, headroom, eyes on the upper third, cut at a joint (pose detection), facial distortion up close, head tilt |
| Landscape / architecture | Horizon in the dead center, sky ratio, empty foreground, tilted / converging verticals |
| Everyday snapshots | Text cut off, tilted text, no focal point (several equally eye-catching things), get closer, flat color and contrast; tips on light and shadow, leading lines, timing, animals, viewpoint |
| Aesthetics | CLIP aesthetic score |

- Severity: Problem (−25) > Warning (−12) > Note (−4) > Tip (no penalty) > ✓
- Score = 100 × e^(−total penalty / 60), split into **Composition / Light / Technical** parts plus a total
- **Root-cause grouping**: problems with the same cause cost points only once (e.g. "busy background", "subject separation" and "subject too small" are one group); the others are marked "same cause"
- **Temporal smoothing**: a problem is reported only when it appears in more than 60% of the last 1.5 s, and removed only when it drops below 30%, so hints do not flicker
- **Auto shutter**: uses the composition score (+ aesthetics); it does not fire while this very moment has a portrait or technical "problem" (closed eyes, blur, not enough depth of field). Light problems do not block it (fixable in post)
- The hint strip shows only the 3 most important hints as short phrases; the checklist on the Guide tab shows everything with the full advice
- Adding a rule: write a `Rule` in `coach/*Rules.kt` and add it to the `all` list; write displayed text in three languages with `tr(Chinese, Japanese, English)`, plus a three-language `short` phrase for the hint strip

### Tuning thresholds (no rebuild needed)
All thresholds live in a config file. On first launch the app exports it to the phone:
`Android/data/com.example.aicomposer/files/coach_config.json`
Edit it over USB or with a file manager on the phone, then **restart the app**. Each key is explained in the comments in `coach/CoachConfig.kt`.
Delete the file and restart to restore the defaults.
Besides the rule thresholds it also has `ai.threads` (CPU threads for the aesthetic model, default 2; more is faster but hotter) and the `guide.*` keys for the direction arrows and the vibration.

### Rule conflicts and "intentional"
- Some rules suggest opposite moves (e.g. the rule of thirds says left, look room says right). The engine keeps only one, by priority:
  look room / lead room > headroom > cut at a joint > subject cut off > eye line > thirds point. The one that yields becomes a "tip" with no penalty.
- Every problem in the checklist has an "Intentional" button: it ignores that rule **in the current scene** (e.g. you really want a centered composition).
  Ignored rules are listed under "Ignored rules" on the Settings tab and can be restored at any time.

### On-device models
| Model | Used for |
|---|---|
| ML Kit face detection (landmarks, eye-open / smile, head angles) | Portrait rules, depth-of-field estimate, look room |
| YOLOX-S object detection (80 classes: people, cars, trains, animals, food…; optional, see below) | Subject, with what it is; also cross-checks body pose so foliage is not taken for a person |
| ML Kit object detection / image labeling | Subject when YOLOX is not installed; scene |
| ML Kit pose detection (33 landmarks) | People whose face is not visible, cut at a joint, full-body detection |
| ML Kit subject segmentation | Generic subject, balance (needs Google Play services; the model downloads on first use) |
| Saliency detection (classic algorithm, every frame) | Finds what "pops" most as the subject, works for any subject; judges whether the frame is simple |
| ML Kit text recognition (Japanese + Latin) | Signs and slogans as subject candidates; text cut off, tilted text |
| CLIP point of interest (about every 2 s) | Splits the frame into 9 regions, finds the most interesting one (orange dashed box), and uses it to pick the subject |
| CLIP + LAION (optional, see below) | Aesthetic score, scene, AI framing |

## Installing the object detector (one-time, recommended)
```bash
python tools/get_detector_model.py
```
Downloads YOLOX-S (Megvii, Apache-2.0, about 36 MB) to `core/src/main/assets/detector.onnx`; then rebuild the app.
- Older phone or laggy: `--size tiny` (about 20 MB, faster, less accurate); more accurate: `--size m` (about 97 MB, about 3× slower)
- Without it the app falls back to ML Kit object detection (5 coarse categories; misses vehicles, animals, ...)
- Detection threshold: `detector.min_score` in `coach_config.json` (default 0.35)

## Installing the AI aesthetic model (one-time, on a computer)
The model is about 96 MB and is not in the Git repository; generate it with the script:
```bash
pip install torch open_clip_torch onnx onnxruntime
python tools/export_clip_model.py
```
The script downloads CLIP and the LAION head, converts them to ONNX and applies int8 quantization (MatMul only; convolution layers stay as they are so that ONNX Runtime on Android can run the model), and writes to `core/src/main/assets/`:
- `clip_image.onnx` (about 96 MB)
- `clip_meta.json`

At the end it prints the "embedding agreement"; close to 1 (> 0.98) means the conversion is fine. Then rebuild the app.
- For higher accuracy: `python tools/export_clip_model.py --no-quant` (about 350 MB, somewhat slower)
- The app works without the model; the AI aesthetic features just show "not installed". If loading fails, the Guide tab shows the reason.

### About the score
- The aesthetic score uses the LAION scale (roughly 1–10). Liveview frames are low-resolution and compressed, so scores are lower than for the final photo; **look at relative differences**.
- The mapping to 0–100 is set by `NORM_LOW` / `NORM_HIGH` in `AestheticModel.kt`; adjust it after real shooting.

## Usage (Sony camera version)
1. a6000: MENU → Application → **Smart Remote Embedded** (not "Send to Smartphone").
2. Connect the phone to the Wi-Fi shown on the camera (DIRECT-xxxx). If it warns that there is no internet, choose to **stay connected**.
3. Open the app and tap "Connect".
4. On the Settings tab, set "Lens focal length" to the focal length you are using (used for the shake calculation).

### Getting more accurate settings advice
- Camera setting: **Live View Display → Setting Effect: ON** (so the liveview brightness reflects the settings).
- **Set ISO to a fixed value** (not AUTO) so the app can work out the suggested ISO.

### Limits of "Apply"
The a6000's shooting mode is set by the mode dial and cannot be changed by the app:
- **Aperture**: dial on A or M
- **Shutter**: dial on S or M
- **ISO / exposure compensation**: usually works in P / A / S (exposure compensation cannot be changed in M)

For anything it cannot set, the app tells you which mode to switch to.

## Planned crop (Sony camera version, when 35–50mm is not long enough)
**The default is "Auto"**: the app decides from the subject size whether to crop and how much to keep:
- People: a face about 15% of the final image height is right; other subjects: about 12% of the final image area
- If the subject is smaller, a crop is planned proportionally (down to 50%); no crop for landscapes or when there is no subject
- If a crop is needed but the lens is not at 50mm yet, it first says "zoom the lens to 50mm" (the camera does not report the focal length to the app; after zooming, set the focal length to 50 on the Settings tab)
- These targets are the `autocrop.*` keys in `coach_config.json`; adjust them to taste

You can also switch to "Manual" on the Settings tab and choose the **width to keep** (80% / 70% / 60% / 50%) and the **final aspect ratio** (3:2, 4:3, 1:1, 4:5, 16:9):
- A **white frame** (expected final image) appears on the viewfinder, the outside is darkened, and the thirds grid is drawn inside the frame
- Composition scores and move hints are computed for the white frame; the goal is that the frame fits and the subject sits on its thirds point
- Cropping magnifies the image: 50mm keeping 60% ≈ 83mm (about 125mm full-frame equivalent). The safe shutter speed uses the effective focal length, and the ISO limit is lowered (60% → 1250)
- Shows how many megapixels remain after cropping and warns below 8 MP
- "AI framing" searches 25 positions and marks the **best-looking crop position** with a purple frame; crop to it afterwards

## Phone camera version (app-phone)
The same coach also runs on the phone's own camera, with no Sony camera needed. It is a separate app ("AI Composer Phone") that can be installed next to the Sony version.
- Same layout and the same rules, AI aesthetics, AI framing, direction arrows and auto shutter as the Sony version (the auto-shutter switch sits in the button row to leave more room for the Guide tab)
- Tap the viewfinder to focus and meter on that spot; zoom and exposure compensation are on the Settings tab
- Photos are saved to `Pictures/AiComposer`
- Phones have a fixed aperture and automatic exposure, so there is no "Suggested settings" / "Apply"; exposure advice points to exposure compensation instead. Shutter speed and ISO are read from the camera, so the shutter and ISO rules still work. The depth-of-field rule is skipped (it is based on the a6000's APS-C sensor)

## Photo score app (app-score)
A third app ("AI Photo Score") scores photos you have already taken, on the phone.
- Pick photos with the system photo picker (no storage permission needed), or select them in your gallery and Share them to the app
- Sorted by aesthetic score (about 1–10); the best 20% are marked **★ Best** and burst shots / near-duplicates **Similar** (same rule as the PC picker)
- Open a photo to see the composition overlay, its rank, the camera settings from EXIF (shutter, aperture, ISO, focal length) and the full checklist; **AI framing** draws the suggested crop (purple frame) with the score it would reach
- Advice is written for a photo that is already taken: what to fix when editing, or what to do next time

## PC photo picker
The same aesthetic model can score a whole folder of photos on a computer (JPG and Sony ARW), remove burst near-duplicates, and copy the best ones to another folder:
```bash
python tools/photo_picker/pick_photos.py D:/Photo/2026-11-17 --top 30
```
See [tools/photo_picker/README.md](tools/photo_picker/README.md).

## Saved settings, performance monitoring, tests
- Focal length, planned crop, auto-shutter threshold and ignored rules are saved automatically and restored next time
- Performance monitoring: the Guide tab shows the time per analysis and the phone temperature. When analysis is too slow or the phone gets hot, it throttles automatically (4 → 2 → 1 times per second), and pauses AI aesthetics when very hot
- Unit tests: `core/src/test/` has tests for the rules, depth-of-field math, cropping, conflict resolution, languages and more; they run on a computer, no phone needed

## Code structure
```
core/        Shared library used by every app (fix a bug here and all apps get it)
├── src/main/java/com/example/aicomposer/
│   ├── analysis/            FrameAnalyzer (ML Kit + brightness / motion / horizon + scene), Features, Saliency,
│   │                        CompositionAdvisor, ParameterAdvisor, AutoCropper
│   ├── coach/               Coaching engine: CoachEngine + rule sets + Optics (depth of field) + CoachConfig
│   ├── ai/AestheticModel.kt CLIP aesthetic score, scene detection, best-crop search (ONNX Runtime)
│   ├── camera/CameraState   Camera settings model (Sony camera or phone camera)
│   ├── pipeline/            CoachPipeline (per-frame analysis → rules → score), DirectionGuide (arrows), AutoShutterGate, settings, performance
│   ├── ui/CoachUi.kt        Shared UI: viewfinder overlay and arrows, hint strip, checklist, AI / crop / auto-shutter sections
│   ├── ui/Theme.kt          Shared colors, type scale and Material theme for all apps
│   └── i18n/I18n.kt         Languages: tr(Chinese, Japanese, English)
├── src/main/assets/         AI model (generated, not in Git)
└── src/test/                Unit tests
app-sony/    Sony camera app: Wi-Fi connection (net/), Sony Camera Remote API (sony/), its own screen and ViewModel
app-phone/   Phone camera app: CameraX (PhoneCamera), its own screen and ViewModel
app-score/   Photo score app: photo picker / Share, EXIF (PhotoLoader), list + detail screens
tools/export_clip_model.py   Generates the AI model files on a computer
tools/photo_picker/          PC photo picker (scores a folder, removes near-duplicates, copies the best)
```

## FAQ
| Symptom | Cause / fix |
|---|---|
| Cannot connect | "Smart Remote Embedded" is not running on the camera, or the phone switched back to your home Wi-Fi |
| Connected but no image | Check the messages in the hint strip and on the Guide tab |
| Suggested ISO shows AUTO | The camera's ISO is AUTO; set it to a fixed value |
| No spirit level | There is no clear enough horizontal line in the frame (normal) |
| Heat / battery drain | Analysis runs about 4 times per second and throttles automatically when slow or hot; AI aesthetics pauses when very hot |
| "Failed to load the aesthetic model" | The reason follows the message; most often the model was exported with an old script, so rerun the export script |
| Gradle sync fails with only a version number (e.g. 25.0.x) | The Gradle JDK is Java 25; switch it to JDK 21 |
