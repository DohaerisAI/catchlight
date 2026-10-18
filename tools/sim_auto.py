#!/usr/bin/env python3
"""Simulate phoneAF's Auto model switching on a bird flying away and back.

Python port of AutoDetector.kt, run frame by frame on synthetic live-view frames where one
reference bird shrinks from 40% to 2% of the frame width and grows back. Compares Auto with
each fixed model on hit rate (bird found) and inference time.
"""
import math, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from bench_birds import Yolo, Pose, detect, iou, build_sweep_refs, to_lv  # noqa
from PIL import Image, ImageFilter
import glob, random

TIERS = ["FAST", "MID", "STRONG"]


class Auto:
    """Line-for-line port of AutoDetector.update/commit."""
    def __init__(self): self.cur, self.pending, self.pf, self.lost, self.slow = "MID", None, 0, 0, False

    def update(self, width, flight, last_ms):
        if width is None:
            self.lost += 1
            want = self.cur if self.lost < 8 else "MID"
        else:
            self.lost = 0
            if self.cur == "FAST": want = "MID" if (width < 0.13 or flight) else "FAST"
            elif self.cur == "MID":
                want = "FAST" if (width > 0.17 and not flight) else ("STRONG" if (width < 0.05 and not flight) else "MID")
            else: want = "MID" if (width > 0.07 or flight) else "STRONG"
        if self.cur == "STRONG" and last_ms > 300: self.slow = True
        if want == "STRONG" and (flight or self.slow): want = "MID"
        if want == self.cur: self.pending, self.pf = None, 0; return self.cur
        if want == self.pending: self.pf += 1
        else: self.pending, self.pf = want, 1
        need = 6 if TIERS.index(want) < TIERS.index(self.cur) else 3
        if self.pf >= need: self.cur, self.pending, self.pf = want, None, 0
        return self.cur


def frames(ref, n=120):
    """Bird recedes 40% -> 2% (log scale) over n/2 frames, then comes back; drifts across."""
    frame, box, _ = ref
    W, H = frame.size
    pad = .15
    bl, bt = max(0, box[0] - pad * (box[2] - box[0])), max(0, box[1] - pad * (box[3] - box[1]))
    br, bb = min(1, box[2] + pad * (box[2] - box[0])), min(1, box[3] + pad * (box[3] - box[1]))
    patch = frame.crop((int(bl * W), int(bt * H), int(br * W), int(bb * H)))
    bg = frame.resize((W // 8, H // 8)).resize((W, H)).filter(ImageFilter.GaussianBlur(12))
    for i in range(n):
        u = i / (n / 2) if i < n / 2 else 2 - i / (n / 2)
        f = math.exp(math.log(0.40) + u * (math.log(0.02) - math.log(0.40)))
        pw = max(4, int((br - bl) * W * f / (box[2] - box[0]))); ph = max(4, int(patch.size[1] * pw / patch.size[0]))
        cx = 0.3 + 0.4 * (i / n); cy = 0.45
        ox, oy = int(cx * W - pw / 2), int(cy * H - ph / 2)
        c = bg.copy(); c.paste(patch.resize((pw, ph), Image.LANCZOS), (ox, oy))
        sx = pw / ((br - bl) * W); sy = ph / ((bb - bt) * H)
        gt = ((ox + (box[0] - bl) * W * sx) / W, (oy + (box[1] - bt) * H * sy) / H,
              (ox + (box[2] - bl) * W * sx) / W, (oy + (box[3] - bt) * H * sy) / H)
        yield f, c, gt


def run(models, refs, flight):
    stats = {k: {"hit": 0, "n": 0, "ms": 0.0, "tiers": {}} for k in ["auto", *models]}
    for ref in refs:
        auto = Auto(); width = None
        for f, canvas, gt in frames(ref):
            for name, m in models.items():
                t = time.perf_counter(); d = detect(m, canvas, True); ms = (time.perf_counter() - t) * 1000
                s = stats[name]; s["n"] += 1; s["ms"] += ms
                s["hit"] += any(iou(b, gt) > 0.3 for _, b in d)
            tier = auto.cur
            m = models[{"FAST": "n320", "MID": "n640", "STRONG": "s640"}[tier]]
            t = time.perf_counter(); d = detect(m, canvas, True); ms = (time.perf_counter() - t) * 1000
            hit = [b for _, b in d if iou(b, gt) > 0.3]
            s = stats["auto"]; s["n"] += 1; s["ms"] += ms; s["hit"] += bool(hit)
            s["tiers"][tier] = s["tiers"].get(tier, 0) + 1
            width = (hit[0][2] - hit[0][0]) if hit else None
            auto.update(width, flight, ms)
    return stats


if __name__ == "__main__":
    base = Path(__file__).resolve().parents[2]
    md = base / "models"
    models = {"n320": Yolo(str(md / "yolo11n_320.tflite")), "n640": Yolo(str(md / "yolo11n_640.tflite")),
              "s640": Yolo(str(md / "yolo11s_640.tflite"))}
    pose = Pose(str(md / "bird_pose.tflite"))
    imgs = sorted(glob.glob(str(base / "testdata/birds/inat_*.jpg")))
    refs = build_sweep_refs(imgs, models["s640"], pose, 6)
    for flight in (False, True):
        st = run(models, refs, flight)
        print(f"\n{'BIF mode' if flight else 'Normal mode'}  ({len(refs)} birds × 120 frames, receding 40%→2% and back)")
        print(f"{'model':8s} {'bird found':>10s} {'avg ms':>8s}   tiers used")
        for k, s in st.items():
            tiers = " ".join(f"{t}:{100 * c // s['n']}%" for t, c in s["tiers"].items())
            print(f"{k:8s} {100 * s['hit'] / s['n']:9.0f}% {s['ms'] / s['n']:8.0f}   {tiers}")
