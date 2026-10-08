# User grade artwork integration, 2026-10-08

Status: development candidate; native render and combined reminder/gallery QA pending. Not a release freeze, signing input, or final visual acceptance.

## Inputs and preservation

The 13 independent transparent originals supplied by the user are bundled byte-for-byte in `app/src/main/assets/export/grades`. The mapping is SSS+, SSS, SS, S, A+, A, A-, B+, B, B-, C, D, E. Original colors, letter shapes, plus/minus signs, glow and spray are retained; there is no recoloring, pixel trimming, shape regeneration, or network request. The earlier small flattened contact sheet and its example percentages are not used.

The source ZIP is Library `libfile_491e575227948191b0a22f7eaeacdc21`, v0, 26,894,473 bytes, SHA-256 `552d065ff9d3ba43de1e06031af71cffc83fb91b74d1605da5696df5fc49fe96`. Sanitized per-file dimensions, checksums and alpha bounds are in `docs/design/user-grade-icons/originals.json`. That manifest deliberately excludes the source computer's paths and conversation URL.

The daily title `export/lettering/title_today.png` is the existing dedicated graffiti “时本 / 今日记录” artwork from design commit 8dbe002. Its generation source and prior candidate are retained in that commit and its design documentation. Only this fixed title is a sprite; date, weekday, percentage, duration, progress ring and labels remain live native rendering. The range title remains the existing native “区间记录”, so a range is never mislabeled as today. No additional artwork is generated in this integration.

## Rendering and data contracts

`GradeArtwork` is the single mapping between the existing settlement grade and its original asset. A known grade always selects its matching asset. “未知” uses an explicit native unknown label and never substitutes E. No grading threshold, calculation, historical snapshot, task record, attention duration or Markdown export changes.

Canvas uses uniform scaling, optical ink bounds for placement, and a safe envelope of x=408–1056, y=420–889 in the 1080×1440 poster. The entire original bitmap, including transparent margins and edge spray, is drawn. Wide S ranks remain large without horizontal distortion. The small KEEP GOING decoration is moved down 40 px to avoid grade overlap.

Original PNG bytes remain full resolution in the APK. Each export decodes one sampled ARGB sprite at a time, at most 512K pixels; the daily title is recycled before grade decoding. No global bitmap cache holds the 13 sprites. The existing bitmap preflight now includes that single additional maximum 2 MiB decode. At 1080×8000 the bounded estimate is 47,142,912 bytes, below 48 MiB. Cancellation and decode failures propagate to the existing atomic-export cleanup path.

Hidden-details PNGs remain 1080×1440 and contain no task names, per-task values, annotations or task count. Full-detail pagination/range appendix, MediaStore behavior and app permissions remain unchanged.

## Reproduction and checks

Run the project's normal unit/lint/build aggregate. `GradeArtworkTest` covers the exact mapping, invalid and unknown states, placement/legibility and overflow-safe sample calculations. The `graffiti` instrumentation suite verifies all 13 bundled hashes and alpha decode bounds, then invokes the real ExportWorker PNG path for every grade. It also covers unknown fields, full task details, extreme date/duration ranges and the existing hidden-details privacy comparison.

On an isolated debug emulator:

```
adb shell am instrument -w -e suite graffiti cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
```

The generated native images are under the target app's files/qa-graffiti directory. Grade fixtures use their correct existing thresholds, and S/SS/SSS/SSS+ fixtures use genuine 100% records. Runtime and visual results must be recorded after execution, not inferred from the static checks.
