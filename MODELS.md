# phoneAF models: what's in and what's out

Pick models in **⚙ Settings → Subject detector** and **Eye / head model**. Pick classes with the **CLASS** button.

## ✅ In the app

### Subject detectors (find the bird, animal, person or object)
| Model | Where | Licence | COCO mAP | Eagle test* | Mac M1 CPU | Use it for |
|---|---|---|---|---|---|---|
| **YOLO11n · 320** *(default)* | bundled | AGPL-3.0 | 39.5† | bird 82% | 17 ms | everyday birding, good balance |
| **YOLO11n · 640** | bundled | AGPL-3.0 | 39.5 | bird 86% | 52 ms | small / distant birds |
| **YOLO11s · 320** | bundled | AGPL-3.0 | 47.0† | bird 91% | 38 ms | more confident, still quick |
| **YOLO11s · 640** | bundled | AGPL-3.0 | 47.0 | bird 93% | 188 ms | best accuracy, recent phones only |
| **EfficientDet-Lite0** | bundled | Apache-2.0 | ~25 | bird 77% | — | old phones, big close subjects |
| **EfficientDet-Lite2** | in-app download (22 MB) | Apache-2.0 | ~33 | bird 90% | — | fully permissive licence |
| **Your own .tflite** | Import | yours | — | — | custom classes, e.g. `bird_head`, `bird_eye` |

\* Your phone photo of the eagle on a monitor, same live-view crop for every model. † mAP is measured at 640; it is lower at 320. Phone speeds are similar to the M1 on a recent flagship and 2–3× slower on mid-range phones.

All of these know the 80 COCO classes. That includes **bird**, 9 mammals (cat, dog, horse, sheep, cow, elephant, bear, zebra, giraffe), **person**, and objects such as airplane, car, motorcycle, bicycle, train, boat and sports ball. Use the **Objects** or **Moving objects** AF mode with the class picker for non-living subjects.

### Eye / head
| Model | Where | Licence | What it does |
|---|---|---|---|
| **BlazeFace** | bundled | Apache-2.0 | human eyes, always on |
| **Estimate from box** | built-in | — | bird/animal head and eye guessed from the box shape and motion (dashed box) |
| **Your pose model** (`*pose*.tflite`) | Import | yours | real bird/animal eye + head keypoints, trained with `tools/train_pose.py` |

## ❌ Not in the app, and why
| Model | Why not (yet) |
|---|---|
| **RF-DETR Nano** (Apache-2.0, 48.4 mAP) | The most accurate small open model, but it is two graphs and ~450 ms on a phone CPU. It only runs fast through LiteRT's new GPU/NPU runtime (~25 ms on a Pixel 8a). That's a good next upgrade. |
| **D-FINE / DEIM nano** (Apache-2.0, ~43 mAP) | No working TFLite export. They are ONNX/TensorRT only. |
| **YOLO-NAS** | Its pretrained weights are non-commercial. |
| **YOLOv9 / v7 original repos** | GPL-3.0, and no better than YOLO11 at this size. |
| **DeepLabCut SuperAnimal** (animal keypoints) | Heavy (HRNet-w32), mixed licence (Apache header, but commercial use goes through EPFL), and it has no bird model. |
| **A ready-made bird-eye detector** | None exists with usable weights and a clear licence. The proper way is to train one, see below. |
| **Sony's own Bird Eye AF** | Not exposed over USB and not on the original a7C (it arrived with the a7 IV, a1 and a7C II). |

## Licences in plain words
- **Apache-2.0** (EfficientDet, BlazeFace, RF-DETR, D-FINE): use them for anything, including commercially. Keep the notice.
- **AGPL-3.0** (Ultralytics YOLO11): compatible with phoneAF, which is itself AGPL-3.0 open source. Publishing phoneAF with YOLO inside is fine as long as the source stays public.
- **Training data for the eye model:** CUB-200-2011 (birds) is limited to non-commercial research and education. AP-10K (animals) is MIT according to its paper. So an eye model trained on CUB is fine for your own use, but it must **not be bundled in released builds**. A public bird-eye model needs eye labels on images we may redistribute, such as your own photos or iNaturalist CC-BY / CC0 images.

## Training a real bird / animal eye model
```bash
pip install ultralytics pillow tqdm
```
```bash
python tools/train_pose.py --cub ~/data/CUB_200_2011 --ap10k ~/data/ap-10k --out ~/data/phoneaf_pose
```
Name the result `something_pose.tflite`, then use **Import** in the app (or `adb push` it). It appears under Eye / head model.

## Which camera settings let phoneAF move the focus
phoneAF reads these from the camera every 2 s. The badge on the live view shows the result, and tapping it fixes the settings.

| Focus Mode | Focus Area | Result |
|---|---|---|
| **AF-C** | **Flexible Spot S / M / L**, Expand Flexible Spot, Tracking: Flexible Spot | ✅ Full control: the point follows the subject and the lens keeps focusing |
| AF-S | Flexible Spot | ⚠️ The point moves, but the camera focuses once per half-press and then locks |
| AF-A / DMF | Flexible Spot | ⚠️ Works, but AF-C is more reliable |
| any AF | Zone, Tracking: Zone | ⚠️ Only moves a big block |
| any AF | Wide, Center, Tracking: Wide or Center | ❌ The camera picks the point itself, so phoneAF can't move it |
| MF | anything | ❌ No autofocus |

The badge also says **"camera follows ✓"** when the camera's own AF frame (cyan box) lands where phoneAF sent it. That is proof the link works.
