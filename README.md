# Catchlight

*(formerly phoneAF; the Android package id stays `com.phoneaf`)*

**Open source, forever:** phoneAF is licensed under the [GNU AGPL-3.0](LICENSE). Anyone may use, study, change and share it. Anyone who distributes a modified version, including as an app, must publish their source under the same licence.

AI subject autofocus for Sony Alpha cameras, running on your Android phone.

phoneAF pulls the live view from your **a7C** over a USB-C cable and runs detection models on the
phone to find **bird eye / head / body, birds in flight, animal eye / head / body, and human eye / face**.
It then moves the camera's Flexible Spot AF point onto that target 10–15 times a second while
holding the shutter half-press, so the a7C's own AF-C drives the lens.

```
 a7C ──USB-C (PTP, Sony PC-Remote)──► phone
   ▲   live-view JPEG ~25 fps            │ EfficientDet subject boxes ─► tracker (velocity, lock-on)
   │                                     │ eye/head keypoints (pose model / BlazeFace)
   └──── AF point (0xD2DC) + S1 hold ◄───┘ lead by latency, smooth, deadband, rate limit
```

## Camera setup (a7C)
1. MENU → Network → **Ctrl w/ Smartphone → Off**
2. MENU → Network → **PC Remote Function**:
   **PC Remote → On**, **PC Remote Cnct Method → USB**, **Still Img. Save Dest. → Camera Only**
   (The a7C has no "PC Remote" under Setup → USB Connection; leave that menu as it is.)
3. **Focus Mode → AF-C**
4. **Focus Area → Flexible Spot (S or M)**. Use S for small birds.
5. Plug a USB-C ↔ USB-C data cable from the camera into the phone and open phoneAF. Accept the USB prompt.

## AF modes
| Mode | Locks on to | Focus point |
|---|---|---|
| Auto | any bird, animal or person (prefers animals) | eye if found, otherwise head |
| Bird · Eye / Head / Body | birds | chosen part |
| Bird in Flight | birds, prefers fast movers | head; body when the head is only estimated in flight |
| Animal · Eye / Head / Body | cat, dog, horse, bear, deer-like COCO mammals | chosen part |
| Animal in Motion | mammals, prefers fast movers | head |
| Human · Eye / Face | people | eye / face |

* **Tap** a subject to lock on to it (LOCKED badge). Tap empty space for a plain spot AF there.
* **AF button** cycles through: OFF (moves the point only) → AUTO (holds S1 while a subject is found) → AF-C HOLD (always holds S1).
* The **dashed** green box means the head/eye was *estimated* from the shape of the box. A **solid** box means a keypoint model found it.
* **Cyan** boxes are the AF frames the camera reports itself (newer PTP3 bodies only).

## Build
1. `scripts/download_models.sh` downloads every model the app bundles: YOLO11 from this repo's releases, EfficientDet and BlazeFace from Google. Add `--big` for EfficientDet-Lite2.
2. Open the folder in **Android Studio** (Ladybug or newer), let it sync, then Run on your phone.
   You can also run `gradle wrapper && ./gradlew installDebug`.

You need Android 9 or newer with USB-OTG (host) support. Most phones from the last 5 years have it.

## Real bird / animal *eye* detection
The stock models give boxes only. True eye detection needs `subject_pose.tflite`, a small YOLO-pose
model trained on bird parts (CUB-200-2011) and animal keypoints (AP-10K):

```
pip install ultralytics pillow tqdm
python tools/train_pose.py --cub ~/data/CUB_200_2011 --ap10k ~/data/ap-10k --out ~/data/phoneaf_pose
cp <exported .tflite> app/src/main/assets/subject_pose.tflite
```
Training takes about 1–2 h on one GPU (Colab works). Without the model, phoneAF still works, but places the
"eye" from the box geometry and motion direction.

## Tuning (⚙)
* **Latency lead**: how far ahead to aim at moving subjects. Live view + AI + USB adds up to ~100–150 ms.
* **Smoothing / Deadband / Max AF updates**: calm vs. snappy. Fewer updates leave more USB time for live view.
* **Lock-on hold**: how long to keep the point after the subject disappears (behind a branch).
* **Tracking stickiness**: how reluctant AF is to jump to a different bird.
* **ROI zoom**: re-runs the detector on a zoomed crop around a small tracked bird.

## Code map
| Path | What |
|---|---|
| `ptp/UsbPtpTransport.kt` | PTP over Android USB host bulk transfers |
| `camera/SonyCamera.kt` | Sony SDIO handshake, live view (0xFFFFC002), AF point (0xD2DC), S1/S2 |
| `camera/CameraSession.kt` | USB permission, single camera I/O thread (commands → AF point → frame) |
| `detect/SubjectDetector.kt` | MediaPipe EfficientDet, COCO birds/mammals/people, ROI zoom |
| `detect/PartLocator.kt` | eye/head: pose model, BlazeFace, or geometric fallback |
| `track/SubjectTracker.kt` | alpha-beta tracker, flight detection, sticky primary subject, tap lock |
| `focus/FocusController.kt` | part selection, latency lead, smoothing, deadband, rate limit |
| `ui/` | Compose UI |

## Status / honesty
* The protocol (handshake, live view, `0xD2DC` AF position, S1/S2) comes from Sony's PC-Remote PTP and was
  proven on real bodies by [YLTai/sony-camera-android](https://github.com/YLTai/sony-camera-android) (a6600, a7C II).
  The **original a7C uses the older protocol-200 path**, the same one as the a6600. It is expected to work but has not been tested on an a7C yet.
  If AF moves don't take effect, check the `AF` readout in the HUD. `OK` means the camera accepted the command.
* Wi-Fi is not supported. The a7C's Wi-Fi remote uses a different, slower path, and USB has much lower latency.

## Licence
phoneAF is free software under the **GNU Affero General Public License v3.0**. See [LICENSE](LICENSE).

Bundled components:
- Ultralytics YOLO11 weights (AGPL-3.0)
- MediaPipe EfficientDet-Lite and BlazeFace (Apache-2.0)
- Protocol knowledge from libgphoto2 (LGPL-2.1) and YLTai/sony-camera-android (MIT). This is reimplemented here, not copied.

Models trained on CUB-200-2011 are **not** distributed with phoneAF, because that dataset is limited to non-commercial research use.

"Sony" and "Alpha" are trademarks of Sony Group Corporation. phoneAF is an independent project and is not affiliated with or endorsed by Sony.
