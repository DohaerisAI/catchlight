#!/usr/bin/env python3
"""
Train phoneAF's bird + animal head/eye keypoint model  ->  subject_pose.tflite

The app crops each detected bird/animal (square, 1.15x the box, black padding), so we train
on exactly those crops. One class ("subject"), 5 keypoints in this order:

    0 tip    (bird beak / animal nose)
    1 eye A  (subject's left eye)
    2 eye B  (subject's right eye)
    3 crown  (top of the head; birds only)
    4 nape   (back of head / neck)

Datasets (download yourself, both free for research):
  * CUB-200-2011  https://www.vision.caltech.edu/datasets/cub_200_2011/   (11,788 birds, 15 parts)
  * AP-10K        https://github.com/AlexTheBad/AP-10K                     (10k mammals, 17 keypoints)

Usage:
  pip install ultralytics pillow tqdm
  python tools/train_pose.py --cub ~/data/CUB_200_2011 --ap10k ~/data/ap-10k --out ~/data/phoneaf_pose
  cp <printed tflite path> app/src/main/assets/subject_pose.tflite

A yolo11n-pose at 256px runs ~8-15 ms per crop on a recent Snapdragon / Tensor phone.
"""
import argparse
import json
import random
from pathlib import Path

from PIL import Image
from tqdm import tqdm

CROP = 256
GROW = 1.15
K = 5


def square_crop(img, box):
    """Mirror of PartLocator.squareCrop in the app."""
    x, y, w, h = box
    side = max(w, h) * GROW
    cx, cy = x + w / 2, y + h / 2
    left, top = cx - side / 2, cy - side / 2
    canvas = Image.new("RGB", (int(side), int(side)))
    canvas.paste(img.crop((int(left), int(top), int(left + side), int(top + side))), (0, 0))
    return canvas.resize((CROP, CROP)), left, top, side


def write_sample(out_dir, split, name, img, box, kpts):
    """kpts: list of K (x, y) in original pixels or None."""
    crop, left, top, side = square_crop(img, box)
    norm = lambda px, py: ((px - left) / side, (py - top) / side)
    bx, by = norm(box[0] + box[2] / 2, box[1] + box[3] / 2)
    bw, bh = box[2] / side, box[3] / side
    parts = []
    for p in kpts:
        if p is None:
            parts += [0, 0, 0]
        else:
            u, v = norm(*p)
            parts += [u, v, 2] if 0 <= u <= 1 and 0 <= v <= 1 else [0, 0, 0]
    if sum(parts[2::3]) == 0:
        return False
    (out_dir / "images" / split).mkdir(parents=True, exist_ok=True)
    (out_dir / "labels" / split).mkdir(parents=True, exist_ok=True)
    crop.save(out_dir / "images" / split / f"{name}.jpg", quality=92)
    line = " ".join(f"{v:.6f}" if isinstance(v, float) else str(v) for v in [0, bx, by, bw, bh, *parts])
    (out_dir / "labels" / split / f"{name}.txt").write_text(line + "\n")
    return True


def convert_cub(root, out):
    root = Path(root)
    paths = dict(l.split() for l in (root / "images.txt").read_text().splitlines())
    boxes = {l.split()[0]: list(map(float, l.split()[1:])) for l in (root / "bounding_boxes.txt").read_text().splitlines()}
    is_train = dict(l.split() for l in (root / "train_test_split.txt").read_text().splitlines())
    # CUB part ids: 2 beak, 5 crown, 7 left eye, 10 nape, 11 right eye
    order = {2: 0, 7: 1, 11: 2, 5: 3, 10: 4}
    parts = {}
    for l in (root / "parts" / "part_locs.txt").read_text().splitlines():
        img_id, pid, x, y, vis = l.split()
        pid = int(pid)
        if pid in order:
            parts.setdefault(img_id, [None] * K)
            if vis == "1":
                parts[img_id][order[pid]] = (float(x), float(y))
    n = 0
    for img_id, rel in tqdm(paths.items(), desc="CUB"):
        img = Image.open(root / "images" / rel).convert("RGB")
        split = "train" if is_train[img_id] == "1" else "val"
        n += write_sample(out, split, f"cub_{img_id}", img, boxes[img_id], parts.get(img_id, [None] * K))
    return n


def convert_ap10k(root, out):
    root = Path(root)
    n = 0
    for split, fname in [("train", "ap10k-train-split1.json"), ("val", "ap10k-val-split1.json")]:
        ann_file = root / "annotations" / fname
        if not ann_file.exists():
            print("skip", ann_file)
            continue
        coco = json.loads(ann_file.read_text())
        files = {im["id"]: im["file_name"] for im in coco["images"]}
        for a in tqdm(coco["annotations"], desc=f"AP-10K {split}"):
            kp = a["keypoints"]
            pt = lambda i: (kp[i * 3], kp[i * 3 + 1]) if kp[i * 3 + 2] > 0 else None
            # AP-10K: 0 L-eye, 1 R-eye, 2 nose, 3 neck
            kpts = [pt(2), pt(0), pt(1), None, pt(3)]
            img_path = root / "data" / files[a["image_id"]]
            if not img_path.exists():
                continue
            img = Image.open(img_path).convert("RGB")
            n += write_sample(out, split, f"ap10k_{a['id']}", img, a["bbox"], kpts)
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cub")
    ap.add_argument("--ap10k")
    ap.add_argument("--out", required=True)
    ap.add_argument("--model", default="yolo11n-pose.pt")
    ap.add_argument("--epochs", type=int, default=120)
    ap.add_argument("--skip-convert", action="store_true")
    args = ap.parse_args()
    out = Path(args.out).expanduser()

    if not args.skip_convert:
        total = 0
        if args.cub:
            total += convert_cub(Path(args.cub).expanduser(), out)
        if args.ap10k:
            total += convert_ap10k(Path(args.ap10k).expanduser(), out)
        print(f"wrote {total} samples")

    yaml = out / "phoneaf_pose.yaml"
    yaml.write_text(
        f"path: {out}\ntrain: images/train\nval: images/val\n"
        f"kpt_shape: [{K}, 3]\nflip_idx: [0, 2, 1, 3, 4]\nnames:\n  0: subject\n"
    )

    from ultralytics import YOLO

    model = YOLO(args.model)
    model.train(data=str(yaml), imgsz=CROP, epochs=args.epochs, batch=64, mosaic=0.0, degrees=15, fliplr=0.5)
    path = model.export(format="tflite", imgsz=CROP)
    print("\nExported:", path)
    print("Copy it to app/src/main/assets/subject_pose.tflite")


if __name__ == "__main__":
    random.seed(0)
    main()
