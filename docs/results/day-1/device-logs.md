# Day-1 device logs (October 6, 2026)

Raw logcat lines from the on-device runs on the test phone (Samsung SM-S928U, SM8650, 11 GB RAM, Android 16). Each block names the command that produced it.

## BioCLIP fp16 vs fp32 probe

Temporary instrumented test `BioclipNanProbeTest` (removed after the run), via `adb shell am instrument -w -e class dev.anchildress1.wildfind.inference.BioclipNanProbeTest dev.anchildress1.wildfind.debug.test/androidx.test.runner.AndroidJUnitRunner`.

```
NanProbe: bitmap 224x224 config=ARGB_8888 premul=false
NanProbe: flora_student_fp16.onnx ALL_OPT nan=1024 inf=0 first=[NaN, NaN, NaN]
NanProbe: flora_student_fp16.onnx NO_OPT nan=0 inf=0 first=[-0.018356323, -0.030456543, -0.0018320084]
NanProbe: flora_student_fp32.onnx ALL_OPT nan=0 inf=0 first=[0.014504992, -1.3622484E-4, 0.0370528]
NanProbe: flora_student_fp32.onnx NO_OPT nan=0 inf=0 first=[0.014504982, -1.3622412E-4, 0.037052803]
```

## BioCLIP parity, fp16 (first run, before the repin)

`make device-test`

```
BioclipParity: load 165 ms, embed 95 ms
BioclipParity: cosine to laptop reference: NaN
```

## BioCLIP parity, fp32

`make device-test`, first fp32 run (cosine divided by the phone vector's norm only):

```
BioclipParity: load 135 ms, embed 58 ms
BioclipParity: cosine to laptop reference: 1.0000002946068118
BioclipParity: scores: oak=0.5199, Carolina horsenettle=0.3828, poison oak=0.3753, poison ivy=0.3445, pokeweed=0.3426, poison sumac=0.2938, screen=0.2533, field=0.1873, person=0.1869, pavement=0.1735, lawn=0.1631, weedy garden bed=0.1507
```

Second run, together with the Gemma test:

```
BioclipParity: load 128 ms, embed 60 ms
BioclipParity: cosine to laptop reference: 1.0000002946068118
```

After the review fix that divides by both norms:

```
BioclipParity: load 138 ms, embed 132 ms
BioclipParity: cosine to laptop reference: 0.9999999987512017
```

## Gemma 4 E2B probe (GPU text and vision backends)

Temporary instrumented test `GemmaProbeTest` (removed after the run). The image is the red oak fixture drawn only in the top-right quadrant of a white 448 x 448 canvas.

```
GemmaProbe: init 9892 ms; pss 2084 MB; avail 2915 MB
GemmaProbe: run0 2886 ms; pss 2889 MB :: Detect every plant in the imag => ```json [   {"box_2d": [0, 500, 497, 1000], "label": "plant"} ] ```
GemmaProbe: run1 2312 ms; pss 2888 MB :: Detect every plant in the imag => ```json [   {"box_2d": [0, 500, 497, 1000], "label": "plant"} ] ```
GemmaProbe: run0 2540 ms; pss 2899 MB :: Detect every plant. Output JSO => ```json [   {"box_2d": [0, 500, 496, 1000], "label": "plant"} ] ```
GemmaProbe: run1 2407 ms; pss 2899 MB :: Detect every plant. Output JSO => ```json [   {"box_2d": [0, 500, 496, 1000], "label": "plant"} ] ```
```

The second prompt asked for `{"boxes":[{"x1":0,"y1":0,"x2":0,"y2":0}]}`; Gemma still answered in native `box_2d`.

## Gemma boxer (warm load)

Instrumented `GemmaBoxerTest` via `make device-test` (the boxer was later removed when Gemma left the verify path):

```
GemmaBoxer: load 4006 ms, pss 2100 MB
GemmaBoxer: box call 4393 ms, pss 2921 MB, boxes [Box(top=0, left=500, bottom=497, right=1000)]
```

## Back camera focus calibration

Temporary instrumented test `FocusProbeTest` (removed after the run). `calib`: 0 uncalibrated, 1 approximate, 2 calibrated; `facing`: 1 back, 0 front.

```
FocusProbe: id=0 facing=1 calib=1 minFocus=null hyperfocal=null focal=[6.3]
FocusProbe: id=1 facing=0 calib=1 minFocus=null hyperfocal=null focal=[3.3]
FocusProbe: id=2 facing=1 calib=1 minFocus=null hyperfocal=null focal=[2.2]
FocusProbe: id=3 facing=0 calib=1 minFocus=null hyperfocal=null focal=[3.3]
```

These are camera characteristics only. Live `CaptureResult.LENS_FOCUS_DISTANCE` readings came later from `make focus-probe`; the raw logs are the `focus-probe-run*.log` files here.

## Bundled assets (S06, S08b)

`make device-test` with every model loaded from the APK:

```
BioclipParity: load 353 ms, embed 45 ms
BioclipParity: cosine to laptop reference: 0.9999999987512017
BioclipParity: scores: oak=0.5199, Carolina horsenettle=0.3828, poison oak=0.3753, poison ivy=0.3445, pokeweed=0.3426, poison sumac=0.2938, screen=0.2533, field=0.1873, person=0.1869, pavement=0.1735, lawn=0.1631, weedy garden bed=0.1507
PlantGateParity: load 220 ms, embed 40 ms
PlantGateParity: cosine to laptop reference: 0.9999999889085937, plant share 0.9869904087790642
OK (3 tests)
```

Same tests after the models switched to memory-mapped, uncompressed APK entries:

```
BioclipParity: load 115 ms, embed 65 ms
BioclipParity: cosine to laptop reference: 0.9999999987512017
PlantGateParity: load 73 ms, embed 30 ms
PlantGateParity: cosine to laptop reference: 0.9999999889085937, plant share 0.9869904087790642
OK (3 tests)
```
