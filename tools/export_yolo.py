#!/usr/bin/env python3
"""
Rebuild Catchlight's bundled YOLO11 detectors from the official Ultralytics weights.

    yolo11n_320.tflite  yolo11n_640.tflite  yolo11s_320.tflite  yolo11s_640.tflite

Output tensor: [1, 84, N], boxes cx,cy,w,h normalized 0..1, 80 sigmoid class scores, no NMS
(the app's YoloModel.kt decodes exactly this).

Needs Python 3.10+ (onnx2tf). Ultralytics 8.4.83+ routes format='tflite' through LiteRT,
which has no FP16 option, so pin 8.4.80:

    pip install "ultralytics==8.4.80" "onnx2tf<1.29" tensorflow tf_keras onnx onnxslim onnxruntime
    python tools/export_yolo.py --out app/src/main/assets

Licence: Ultralytics YOLO11 weights are AGPL-3.0, the same licence as Catchlight.
"""
import argparse
import glob
import shutil
from pathlib import Path

from ultralytics import YOLO


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="app/src/main/assets")
    ap.add_argument("--int8", action="store_true", help="full-integer quantization (needs --calib)")
    ap.add_argument("--calib", help="dataset YAML for INT8 calibration images")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for size in ("n", "s"):
        for imgsz in (320, 640):
            model = YOLO(f"yolo11{size}.pt")
            kw = dict(format="tflite", imgsz=imgsz, nms=False)
            if a.int8:
                kw.update(int8=True, data=a.calib)
            else:
                kw.update(half=True)
            saved = Path(model.export(**kw))
            # integer_quant = INT8 maths with float32 input/output (what YoloModel.kt feeds); the
            # full_integer_quant variant would need int8 input handling in the app.
            pattern = "*[!l]_integer_quant.tflite" if a.int8 else "*_float16.tflite"
            src = saved if saved.suffix == ".tflite" else Path(sorted(glob.glob(str(saved / pattern)))[0])
            dst = out / (f"yolo11{size}_{imgsz}" + ("_int8" if a.int8 else "") + ".tflite")
            shutil.copy(src, dst)
            print("→", dst, dst.stat().st_size // 1_000_000, "MB")


if __name__ == "__main__":
    main()
