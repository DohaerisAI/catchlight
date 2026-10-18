#!/usr/bin/env bash
# Fetches the on-device models into app/src/main/assets/.
#   - MediaPipe EfficientDet-Lite0 + BlazeFace (Apache-2.0) from Google
#   - YOLO11 detectors (AGPL-3.0) from this repo's GitHub release (rebuild them with tools/export_yolo.py)
# Usage: scripts/download_models.sh [--big]   (--big also fetches EfficientDet-Lite2: slower, better on small birds)
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/assets"
base=https://storage.googleapis.com/mediapipe-models
fetch() { [ -f "$2" ] || { echo "↓ $2"; curl -fL --progress-bar -o "$2" "$1"; }; }

fetch "$base/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite" efficientdet_lite0.tflite
fetch "$base/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite" blaze_face_short_range.tflite
release=https://github.com/DohaerisAI/catchlight/releases/download/models-v2
for m in yolo11n_320_int8 yolo11n_640_int8 yolo11s_320_int8 yolo11s_640_int8; do
  fetch "$release/$m.tflite" "$m.tflite"
done
if [ "${1:-}" = "--big" ]; then
  fetch "$base/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite" efficientdet_lite2.tflite
fi
echo "Models in $(pwd):"; ls -lh *.tflite
[ -f subject_pose.tflite ] || echo "Note: no subject_pose.tflite yet – bird/animal eye will be estimated. See tools/train_pose.py."
