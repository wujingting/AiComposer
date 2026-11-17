"""
Exports the CLIP ViT-B/32 image encoder + LAION aesthetic head in a format the phone can use.

Run it once on a computer (needs internet to download the models, about 350MB):
    pip install torch open_clip_torch onnx onnxruntime
    python tools/export_clip_model.py

Output goes to core/src/main/assets/:
    clip_image.onnx   image encoder (int8-quantized by default, about 90MB)
    clip_meta.json    preprocessing parameters, aesthetic head weights, scene / composition text embeddings
"""
import argparse
import json
import os
import urllib.request

import numpy as np
import onnxruntime as ort
import open_clip
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

HEAD_URL = "https://github.com/LAION-AI/aesthetic-predictor/raw/main/sa_0_4_vit_b_32_linear.pth"
MEAN = [0.48145466, 0.4578275, 0.40821073]
STD = [0.26862954, 0.26130258, 0.27577711]

# Text prompts for zero-shot scene classification (keys must match SceneType in the app)
SCENES = {
    "PORTRAIT": ["a portrait photo of a person", "a photo of people"],
    "LANDSCAPE": ["a landscape photo", "a photo of scenery with mountains, sea or sky", "a cityscape photo"],
    "NIGHT": ["a photo taken at night", "a low light photo"],
    "ACTION": ["a sports photo", "a photo of a fast moving subject"],
    "CLOSEUP": ["a close-up photo", "a macro photo", "a photo of food on a table"],
    "GENERAL": ["a snapshot", "a photo of an everyday scene"],
}
COMPOSITION = ["a well-composed photo", "a poorly composed photo"]

# "Point of interest" detection: which part of the frame is most interesting (used to pick the subject in snapshots)
INTEREST_POS = [
    "a photo of an interesting object", "a funny sign", "a cute animal",
    "a beautiful detail", "something unusual", "an eye-catching thing",
]
INTEREST_NEG = [
    "a plain wall", "an empty background", "a boring photo of nothing", "blank pavement", "plain sky",
]


class Visual(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        return self.m.encode_image(x)


def main():
    ap = argparse.ArgumentParser()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--out", default=os.path.join(root, "core", "src", "main", "assets"))
    ap.add_argument("--no-quant", action="store_true", help="do not quantize (about 350MB, highest accuracy)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    print("1/5 Loading CLIP ViT-B/32 ...")
    model, _, _ = open_clip.create_model_and_transforms("ViT-B-32-quickgelu", pretrained="openai")
    model.eval()
    tok = open_clip.get_tokenizer("ViT-B-32-quickgelu")

    def text_emb(prompts):
        with torch.no_grad():
            e = model.encode_text(tok(prompts)).float()
            e = e / e.norm(dim=-1, keepdim=True)
            m = e.mean(0)
            return (m / m.norm()).tolist()

    print("2/5 Exporting ONNX ...")
    fp32_path = os.path.join(args.out, "clip_image_fp32.onnx")
    final_path = os.path.join(args.out, "clip_image.onnx")
    dummy = torch.randn(1, 3, 224, 224)
    torch.onnx.export(
        Visual(model), dummy, fp32_path,
        input_names=["pixel_values"], output_names=["image_embeds"],
        dynamic_axes={"pixel_values": {0: "batch"}, "image_embeds": {0: "batch"}},
        opset_version=17,
        # PyTorch 2.9+ defaults to the new exporter (needs onnxscript); pin the legacy TorchScript exporter
        dynamo=False,
    )

    if args.no_quant:
        os.replace(fp32_path, final_path)
    else:
        print("3/5 int8 quantization ...")
        # Quantize MatMul/Gemm only: Conv would become ConvInteger, which ONNX Runtime for Android does not support
        quantize_dynamic(fp32_path, final_path, weight_type=QuantType.QInt8,
                         op_types_to_quantize=["MatMul", "Gemm"])
        os.remove(fp32_path)

    print("4/5 Downloading the LAION aesthetic head ...")
    head_path = os.path.join(args.out, "_head.pth")
    urllib.request.urlretrieve(HEAD_URL, head_path)
    sd = torch.load(head_path, map_location="cpu")
    w = sd["weight"].reshape(-1).float().tolist()
    b = float(sd["bias"].reshape(-1)[0])
    os.remove(head_path)

    meta = {
        "model": "ViT-B-32/openai + LAION sa_0_4_vit_b_32_linear",
        "image_size": 224,
        "mean": MEAN,
        "std": STD,
        "logit_scale": 100.0,
        "head": {"w": w, "b": b},
        "scenes": {k: text_emb(v) for k, v in SCENES.items()},
        "composition": {"good": text_emb([COMPOSITION[0]]), "bad": text_emb([COMPOSITION[1]])},
        "interest": {"pos": text_emb(INTEREST_POS), "neg": text_emb(INTEREST_NEG)},
    }
    with open(os.path.join(args.out, "clip_meta.json"), "w") as f:
        json.dump(meta, f)

    print("5/5 Self-check: ONNX vs PyTorch ...")
    x = torch.randn(2, 3, 224, 224)
    with torch.no_grad():
        ref = model.encode_image(x).float().numpy()
    sess = ort.InferenceSession(final_path, providers=["CPUExecutionProvider"])
    out = sess.run(None, {"pixel_values": x.numpy()})[0]
    cos = (ref * out).sum(1) / (np.linalg.norm(ref, axis=1) * np.linalg.norm(out, axis=1))
    print(f"   Embedding agreement (cosine similarity, closer to 1 is better): {cos.round(4).tolist()}")
    size_mb = os.path.getsize(final_path) / 1e6
    print(f"Done! {final_path} ({size_mb:.0f} MB) and clip_meta.json are ready; rebuild the app.")


if __name__ == "__main__":
    main()
