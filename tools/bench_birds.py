#!/usr/bin/env python3
"""
phoneAF bird benchmark: runs the same pipeline as the Android app on a folder of photos.

  detector (YOLO11 TFLite, plain resize) -> optional ROI-zoom second pass -> square crop
  -> bird_pose.tflite -> eye / head point

Two tests:
  real   every photo, rendered at a7C live-view size (1024 px wide), per category
  sweep  close-up birds shrunk into a wide frame (40% -> 2% of width) with known ground truth

Usage:
  python tools/bench_birds.py --images ../testdata/birds --models ../models --out ../testdata/report
"""
import argparse, glob, html, json, math, os, random, time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from ai_edge_litert.interpreter import Interpreter

LV_W = 1024          # a7C live-view JPEG width
BIRD = 14            # COCO index of "bird"
MIN_SCORE = 0.35     # app default
KP_CONF = 0.4        # app default
GRID_W, GRID_H = 640, 480


# ---------------------------------------------------------------- models (mirror the Kotlin code)

class Yolo:
    def __init__(self, path):
        self.ip = Interpreter(model_path=path, num_threads=4)
        self.ip.allocate_tensors()
        self.inp = self.ip.get_input_details()[0]
        self.out = self.ip.get_output_details()[0]
        self.size = self.inp["shape"][1]
        self.name = Path(path).stem

    def run(self, img, min_score):
        """Plain resize (no letterbox), like YoloModel.kt. Returns [(score, (l,t,r,b) normalized)]."""
        x = np.asarray(img.resize((self.size, self.size), Image.BILINEAR), dtype=np.float32)[None] / 255.0
        self.ip.set_tensor(self.inp["index"], x)
        self.ip.invoke()
        o = self.ip.get_tensor(self.out["index"])[0]          # [84, N]
        if o.shape[0] > o.shape[1]:
            o = o.T
        scores = o[4:].max(0); cls = o[4:].argmax(0)
        keep = (cls == BIRD) & (scores >= min_score)
        dets = []
        for i in np.where(keep)[0]:
            cx, cy, w, h = o[:4, i]
            dets.append((float(scores[i]), (max(0., cx - w / 2), max(0., cy - h / 2), cx + w / 2, cy + h / 2)))
        return nms(dets, 0.5)


class Pose:
    def __init__(self, path):
        self.ip = Interpreter(model_path=path, num_threads=4)
        self.ip.allocate_tensors()
        self.inp = self.ip.get_input_details()[0]
        self.out = self.ip.get_output_details()[0]
        self.size = self.inp["shape"][1]

    def locate(self, frame, box):
        """Square crop 1.15x around box (black padding) -> keypoints in frame-normalized coords."""
        W, H = frame.size
        l, t, r, b = box[0] * W, box[1] * H, box[2] * W, box[3] * H
        side = max(r - l, b - t) * 1.15
        cx, cy = (l + r) / 2, (t + b) / 2
        x0, y0 = cx - side / 2, cy - side / 2
        crop = Image.new("RGB", (max(1, int(side)), max(1, int(side))))
        crop.paste(frame.crop((int(x0), int(y0), int(x0 + side), int(y0 + side))), (0, 0))
        x = np.asarray(crop.resize((self.size, self.size), Image.BILINEAR), dtype=np.float32)[None] / 255.0
        self.ip.set_tensor(self.inp["index"], x)
        self.ip.invoke()
        o = self.ip.get_tensor(self.out["index"])[0]          # [20, N]
        best = o[4].argmax()
        if o[4, best] < 0.3:
            return None
        scale = self.size if max(o[0, best], o[1, best]) > 1.5 else 1.0
        kps = []
        for k in range(5):
            px, py, c = o[5 + 3 * k, best] / scale, o[6 + 3 * k, best] / scale, o[7 + 3 * k, best]
            kps.append(((x0 + px * side) / W, (y0 + py * side) / H, float(c)))
        return kps


def nms(dets, iou_thr):
    out = []
    for d in sorted(dets, key=lambda d: -d[0]):
        if all(iou(d[1], o[1]) <= iou_thr for o in out):
            out.append(d)
    return out


def iou(a, b):
    iw = max(0., min(a[2], b[2]) - max(a[0], b[0])); ih = max(0., min(a[3], b[3]) - max(a[1], b[1]))
    inter = iw * ih
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / u if u > 0 else 0.


def detect(model, frame, roi_zoom):
    """Full frame + (like SubjectDetector.kt) a 4x crop around a small best box."""
    dets = model.run(frame, MIN_SCORE)
    if roi_zoom:
        # Without a tracker we zoom around the best box, or the centre if nothing was found
        # (a photographer keeps the bird near the middle; the app zooms around the tracked box).
        ref = dets[0][1] if dets else (0.45, 0.45, 0.55, 0.55)
        w = ref[2] - ref[0]
        if w < 0.18:
            side = max(max(w, ref[3] - ref[1]) * 4, 0.25)
            cx, cy = (ref[0] + ref[2]) / 2, (ref[1] + ref[3]) / 2
            crop = (max(0., cx - side / 2), max(0., cy - side / 2), min(1., cx + side / 2), min(1., cy + side / 2))
            W, H = frame.size
            sub = frame.crop((int(crop[0] * W), int(crop[1] * H), int(crop[2] * W), int(crop[3] * H)))
            for s, b in model.run(sub, MIN_SCORE * 0.85):
                cw, ch = crop[2] - crop[0], crop[3] - crop[1]
                g = (crop[0] + b[0] * cw, crop[1] + b[1] * ch, crop[0] + b[2] * cw, crop[1] + b[3] * ch)
                dup = [i for i, d in enumerate(dets) if iou(d[1], g) > 0.3]
                if not dup:
                    dets.append((s, g))
                elif s > dets[dup[0]][0]:
                    dets[dup[0]] = (s, g)
    return sorted(dets, key=lambda d: -d[0])


def pick_eye(kps):
    """Like FocusController: visible eye nearest the head centre, else head centre."""
    vis = [k for k in kps if k[2] >= KP_CONF]
    if not vis:
        return None, None, "none"
    head = (sum(k[0] for k in vis) / len(vis), sum(k[1] for k in vis) / len(vis))
    eyes = [k for k in (kps[1], kps[2]) if k[2] >= KP_CONF]
    if eyes:
        e = min(eyes, key=lambda k: math.hypot(k[0] - head[0], k[1] - head[1]))
        return (e[0], e[1]), head, "eye"
    return head, head, "head"


def to_lv(img):
    w, h = img.size
    return img.resize((LV_W, max(1, int(h * LV_W / w))), Image.BILINEAR)


# ---------------------------------------------------------------- real photos

def run_real(images, models, pose, out_dir, meta):
    rows = []
    vis_dir = out_dir / "real"; vis_dir.mkdir(parents=True, exist_ok=True)
    for path in images:
        name = Path(path).name
        cat = meta.get(name, {}).get("category", name.split("_")[0])
        try:
            frame = to_lv(Image.open(path).convert("RGB"))
        except Exception:
            continue
        row = {"file": name, "category": cat, "species": meta.get(name, {}).get("species", "")}
        vis = frame.copy(); d = ImageDraw.Draw(vis)
        for mi, m in enumerate(models):
            t0 = time.perf_counter(); dets = detect(m, frame, roi_zoom=True); ms = (time.perf_counter() - t0) * 1000
            r = {"n": len(dets), "ms": round(ms, 1)}
            if dets:
                s, b = dets[0]
                r.update(score=round(s, 2), width=round(b[2] - b[0], 3))
                t1 = time.perf_counter(); kps = pose.locate(frame, b); r["pose_ms"] = round((time.perf_counter() - t1) * 1000, 1)
                pt, head, kind = pick_eye(kps) if kps else (None, None, "none")
                r["part"] = kind
                if mi == len(models) - 1:  # draw the strongest model's result
                    W, H = frame.size
                    for s2, b2 in dets:
                        d.rectangle([b2[0] * W, b2[1] * H, b2[2] * W, b2[3] * H], outline=(255, 176, 0), width=2)
                    if kps:
                        for k in kps:
                            if k[2] >= KP_CONF:
                                d.ellipse([k[0] * W - 3, k[1] * H - 3, k[0] * W + 3, k[1] * H + 3], outline="yellow")
                    if pt:
                        c = (124, 252, 154) if kind == "eye" else (255, 176, 0)
                        d.line([pt[0] * W - 10, pt[1] * H, pt[0] * W + 10, pt[1] * H], fill=c, width=2)
                        d.line([pt[0] * W, pt[1] * H - 10, pt[0] * W, pt[1] * H + 10], fill=c, width=2)
            row[m.name] = r
        vis.save(vis_dir / name, quality=85)
        rows.append(row)
    return rows


# ---------------------------------------------------------------- synthetic distance sweep

FRACTIONS = [0.40, 0.25, 0.15, 0.10, 0.07, 0.05, 0.035, 0.025, 0.02]


def build_sweep_refs(images, ref_model, pose, n):
    """Close-up birds where the strongest model and the pose model are both confident."""
    refs = []
    for path in images:
        try:
            img = Image.open(path).convert("RGB")
        except Exception:
            continue
        frame = to_lv(img)
        dets = ref_model.run(frame, 0.6)
        if len(dets) != 1:
            continue
        s, b = dets[0]
        if b[2] - b[0] < 0.25:
            continue
        kps = pose.locate(frame, b)
        if not kps:
            continue
        pt, head, kind = pick_eye(kps)
        if kind != "eye":
            continue
        refs.append((frame, b, pt))
        if len(refs) >= n:
            break
    return refs


def run_sweep(refs, models, pose, out_dir):
    random.seed(3)
    res = {m.name: {f: {"det": 0, "eye_ok": 0, "err": [], "n": 0} for f in FRACTIONS} for m in models}
    res_nozoom = {m.name: {f: 0 for f in FRACTIONS} for m in models}
    sample_dir = out_dir / "sweep"; sample_dir.mkdir(parents=True, exist_ok=True)
    for ri, (frame, box, eye) in enumerate(refs):
        W, H = frame.size
        pad = 0.15
        bl, bt = max(0., box[0] - pad * (box[2] - box[0])), max(0., box[1] - pad * (box[3] - box[1]))
        br, bb = min(1., box[2] + pad * (box[2] - box[0])), min(1., box[3] + pad * (box[3] - box[1]))
        patch = frame.crop((int(bl * W), int(bt * H), int(br * W), int(bb * H)))
        bg = frame.resize((W // 8, H // 8)).resize((W, H)).filter(ImageFilter.GaussianBlur(12))
        for f in FRACTIONS:
            pw = max(4, int((br - bl) * W * f / (box[2] - box[0])))      # bird box width = f * frame
            ph = max(4, int(patch.size[1] * pw / patch.size[0]))
            small = patch.resize((pw, ph), Image.LANCZOS)
            ox = random.randint(int(W * 0.2), int(W * 0.8) - pw) if W * 0.6 > pw else (W - pw) // 2
            oy = random.randint(int(H * 0.2), max(int(H * 0.2), int(H * 0.8) - ph)) if H * 0.6 > ph else (H - ph) // 2
            canvas = bg.copy(); canvas.paste(small, (ox, oy))
            # ground truth in canvas coords
            sx = pw / ((br - bl) * W); sy = ph / ((bb - bt) * H)
            gt_box = ((ox + (box[0] - bl) * W * sx) / W, (oy + (box[1] - bt) * H * sy) / H,
                      (ox + (box[2] - bl) * W * sx) / W, (oy + (box[3] - bt) * H * sy) / H)
            gt_eye = ((ox + (eye[0] - bl) * W * sx) / W, (oy + (eye[1] - bt) * H * sy) / H)
            if ri < 2:
                canvas.save(sample_dir / f"ref{ri}_{int(f * 1000):03d}.jpg", quality=85)
            for m in models:
                cell = res[m.name][f]; cell["n"] += 1
                if any(iou(b2, gt_box) > 0.3 for _, b2 in m.run(canvas, MIN_SCORE)):
                    res_nozoom[m.name][f] += 1
                dets = detect(m, canvas, roi_zoom=True)
                hit = [d for d in dets if iou(d[1], gt_box) > 0.3]
                if not hit:
                    continue
                cell["det"] += 1
                kps = pose.locate(canvas, hit[0][1])
                if not kps:
                    continue
                pt, head, kind = pick_eye(kps)
                if pt is None:
                    continue
                err = math.hypot((pt[0] - gt_eye[0]) * GRID_W, (pt[1] - gt_eye[1]) * GRID_H)
                cell["err"].append(err)
                if err <= 15:
                    cell["eye_ok"] += 1
    return res, res_nozoom


# ---------------------------------------------------------------- report

def report(out_dir, models, rows, sweep, sweep_nozoom, n_refs, meta):
    cats = sorted({r["category"] for r in rows})
    def pct(a, b): return f"{100 * a / b:.0f}%" if b else "–"

    real_tbl = ["<tr><th>Category</th><th>Photos</th>" + "".join(
        f"<th>{m.name}<br><small>bird found · eye found · ms</small></th>" for m in models) + "</tr>"]
    for c in cats + ["ALL"]:
        rs = [r for r in rows if c == "ALL" or r["category"] == c]
        cells = []
        for m in models:
            found = sum(1 for r in rs if r[m.name]["n"] > 0)
            eye = sum(1 for r in rs if r[m.name].get("part") == "eye")
            ms = np.median([r[m.name]["ms"] for r in rs]) if rs else 0
            cells.append(f"<td>{pct(found, len(rs))} · {pct(eye, len(rs))} · {ms:.0f}</td>")
        real_tbl.append(f"<tr><td><b>{c}</b></td><td>{len(rs)}</td>{''.join(cells)}</tr>")

    sw = ["<tr><th>Bird width<br><small>(% of frame / px in live view)</small></th>" + "".join(
        f"<th>{m.name}<br><small>found (no zoom → zoom) · eye ≤15 grid px · median err</small></th>" for m in models) + "</tr>"]
    for f in FRACTIONS:
        cells = []
        for m in models:
            c = sweep[m.name][f]; nz = sweep_nozoom[m.name][f]
            med = f"{np.median(c['err']):.0f}px" if c["err"] else "–"
            cells.append(f"<td>{pct(nz, c['n'])} → {pct(c['det'], c['n'])} · {pct(c['eye_ok'], c['n'])} · {med}</td>")
        sw.append(f"<tr><td><b>{f * 100:.1f}%</b> / {int(f * LV_W)} px</td>{''.join(cells)}</tr>")

    gallery = []
    for r in rows:
        last = models[-1].name
        info = r[last]
        cap = f"{html.escape(r['category'])} · {html.escape(r.get('species', ''))} · " + (
            f"bird {info.get('score', 0):.2f} · {info.get('part', '')}" if info["n"] else "<b style='color:#f66'>no bird</b>")
        src = meta.get(r["file"], {})
        credit = html.escape(f"{src.get('author', '')} · {src.get('license', '')}")[:140]
        gallery.append(f"<figure><img src='real/{r['file']}' loading=lazy><figcaption>{cap}<br><small>{credit}</small></figcaption></figure>")

    samples = "".join(f"<img src='sweep/{Path(p).name}'>" for p in sorted(glob.glob(str(out_dir / 'sweep' / '*.jpg'))))
    page = f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>phoneAF bird benchmark</title>
<style>body{{font:14px system-ui;margin:16px;background:#111;color:#eee}}table{{border-collapse:collapse;margin:8px 0 24px}}
td,th{{border:1px solid #333;padding:6px 8px;text-align:left}}th{{background:#222}}h1{{color:#ffb000}}
.g{{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:8px}}figure{{margin:0}}figure img,.s img{{width:100%}}
figcaption{{font-size:12px;color:#aaa}}.s{{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:6px}}</style>
<h1>phoneAF bird benchmark</h1>
<p>Same pipeline as the app: YOLO11 detector (bird ≥ {MIN_SCORE}, ROI zoom on) → 1.15× square crop → bird_pose (eye ≥ {KP_CONF}).
Everything is rendered at a7C live-view width ({LV_W}px). Mac M1 CPU timings, 4 threads.</p>
<h2>Real photos</h2><table>{''.join(real_tbl)}</table>
<h2>Distance sweep ({n_refs} birds × {len(FRACTIONS)} sizes)</h2>
<p>"eye ≤15 grid px" = predicted eye within 15 px of the true eye on Sony's 640×480 AF grid (≈ inside Flexible Spot S).</p>
<table>{''.join(sw)}</table><div class=s>{samples}</div>
<h2>Gallery ({models[-1].name})</h2><p>Orange = bird box, yellow = keypoints, green cross = AF target on eye, orange cross = head fallback.</p>
<div class=g>{''.join(gallery)}</div>"""
    (out_dir / "index.html").write_text(page)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--sweep-birds", type=int, default=20)
    ap.add_argument("--names", default="yolo11n_320,yolo11n_640,yolo11s_640",
                    help="comma-separated model files (without .tflite) to compare; the last one is drawn in the gallery")
    a = ap.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    md = Path(a.models)
    models = [Yolo(str(md / (n + ".tflite"))) for n in a.names.split(",")]
    pose = Pose(str(md / "bird_pose.tflite"))
    images = sorted(p for p in glob.glob(os.path.join(a.images, "*")) if p.lower().endswith((".jpg", ".jpeg", ".png")))
    meta_path = Path(a.images) / "attribution.json"
    meta = {m["file"]: m for m in json.loads(meta_path.read_text())} if meta_path.exists() else {}
    print(f"{len(images)} images")
    rows = run_real(images, models, pose, out, meta)
    refs = build_sweep_refs([p for p in images if "inat_" in p], models[-1], pose, a.sweep_birds)
    print(f"sweep refs: {len(refs)}")
    sweep, nozoom = run_sweep(refs, models, pose, out)
    report(out, models, rows, sweep, nozoom, len(refs), meta)
    (out / "results.json").write_text(json.dumps({"rows": rows}, indent=1, default=float))
    print("report:", out / "index.html")


if __name__ == "__main__":
    main()
