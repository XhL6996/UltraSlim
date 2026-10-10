# 2026-10-10 libultrahdr upstream and HEIC rebuild evaluation

The user now accepts a regenerated gain map when appearance is preserved. Pixel-exact map preservation is therefore no longer the sole acceptance criterion for future design; existing explicit strict-mode promises must still remain truthful.

## Decision

Selectively backport three color fixes, with route-specific tests before release. Do not replace the entire vendored snapshot with main or switch all exports to rebuild based on these results. This investigation changes no production codec implementation or app default and installs no APK.

Upstream main `66821e0a261aa3a06c0e7c889f52eced52850be1` is 32 commits ahead of v2.0.2, checked against the GitHub compare API on 2026-10-10. Candidate fixes:

- [RGB888 green channel](https://github.com/google/libultrahdr/commit/924967a75909cd5433bb6dcc835abab57d785313).
- [ARM NEON gamut conversion](https://github.com/google/libultrahdr/commit/2472ff7ad1f204f802495980c61b6a8c12fc1df0).
- [sRGB NCLX transfer](https://github.com/google/libultrahdr/commit/b4880a1c21e7f7e9ed84a82ed78f8e1cd00262c9).

The final-ISO metadata-layout change (`d911e4b6dd`) needs old-file and platform regression testing separately. The three selected fixes are real, but are not evidence that they caused the previously reported preserve-path hue problem.

## What official HEIC support means

The public encoder accepts raw HDR alone (API0) or raw SDR + HDR (API1) for HEIC/AVIF. Compressed base + compressed gain-map input is explicitly rejected for these outputs in `ultrahdr_api.cpp`. The internal raw-base/raw-map/metadata HEIC writer exists but is private. HEIC can preserve a gain map; UltraSlim already implements that container path. Rebuilding is an additional way to derive a new map, not a guarantee of unchanged rendering.

## Isolated experiment

Artifacts and complete logs: `build/validation-dependencies/rebuild-20261010/` (ignored).

`experiment/` is a copy of current native code and vendored libultrahdr plus the three selected fixes. Existing local patches, notably quality remapping, remain. The first two upstream patches applied; for the third only the two source hunks were applied because its upstream test hunk requires other intervening main changes. This is not an unmodified upstream binary.

`experiment/native/host_test/rebuild_probe.cpp` uses the public API: decode JPEG HDR to RGBA1010102/HLG; separately decode the original SDR to RGBA8888/sRGB; supply both; set target peak to parsed `hdr_capacity_max * 203` nits within API limits. Main quality 92, gain-map quality 95, scale 2, RGB gain map, speed 1, four codec threads. Source decoder's SDR descriptor leaves transfer unspecified, so the harness explicitly sets sRGB, matching the requested decoded representation. The harness is diagnostic, not a complete replacement for production orientation/EXIF/grid/validation handling.

Preserve comparison uses the current production host executable, quality 92, speed 1, four threads, HEIC compatibility enabled, no resizing/retry. Preserve and rebuild do not have equivalent gain-map quantization settings; timing and size are observations, not an optimization benchmark.

Apple comparison uses ImageIO/Core Image rendered in extended linear sRGB at long edge <=768. Full SDR and HDR use `AppleColorAudit.swift`; `AppleHeadroomAudit.swift` applies the file's own auxiliary gain map to its SDR base at headrooms 1.5, 2 and 3 using `applyingGainMap(_:headroom:)`. Raw gain-map differences are expected after rebuilding and are not an appearance error metric.

## Measurements

Each percentage below is the largest absolute relative difference of the three whole-image linear channel means. These are diagnostic averages, not pixelwise error bounds or visual-equivalence claims. The logs also contain linear MAE and peaks.

| Sample | Route | Bytes | SDR | Intermediate HDR (1.5/2/3) | Full HDR |
| --- | --- | ---: | ---: | ---: | ---: |
| 66 | preserve compatible | 2,562,043 | 0.307% | 0.328% | 0.344% |
| 66 | rebuild dual intent | 3,254,068 | 0.180% | 0.189% | 0.314% |
| 78 | preserve compatible | 7,661,100 | 0.578% | 0.589% | 0.590% |
| 78 | rebuild dual intent | 7,129,875 | 0.036% | 0.427% | 0.647% |
| 82 | preserve compatible | 3,695,658 | 0.198% | 0.278% | 0.359% |
| 82 | rebuild dual intent | 4,255,597 | 0.128% | 1.546% | 1.777% |
| apple | preserve compatible | 38,134 | 0.243% | 14.280% | 50.971% |
| apple | rebuild dual intent | 39,119 | 0.054% | 14.235% | 50.754% |

- Sample 66 is the earlier OPPO issue source (`build/validation-heic-color/iphone-followup/source.jpg`). 78/82 are two other real JPEGs pulled from the connected phone. `apple` is upstream `apple_gainmap_new.jpg`.
- Sample 66 current compatible export is 2,562,043 bytes / 2,712 ms; dual-intent rebuild is 3,254,068 bytes / 4,125 ms on this host. Rebuild is not automatically smaller or faster.
- Patched HDR-only API0 on sample 66 still changes SDR means by about -11.5%, -11.5%, -13.7%; supplying original SDR substantially improves this. API0 uses different default processing from API1.
- Apple fixture exposes a separate source-interpretation discrepancy: Apple Core Image reports source full headroom 8; libultrahdr parses 23.1475. Both current preserve and the dual-intent rebuild produce excessive HDR brightness. This is not established as a new rebuild regression or a decoder defect. The Apple metadata/headroom interpretation must be investigated before making a universal rendering-equivalence claim.

## Validation and limits

Both existing host CTest suites passed against the isolated patched copy (2/2, 14.26 seconds). All four dual-intent outputs encoded and decoded with Apple Core Image; intermediate-headroom diagnostics also completed. No new Android decoding, Google Photos visual check or physical iPhone Photos acceptance was performed for these experimental outputs. Previously confirmed iPhone appearance applies only to the earlier compatible sample, not these rebuilt files.

Next implementation should preserve the original SDR appearance, validate HDR at multiple headrooms, and resolve the Apple source interpretation before treating rebuild as a universal fallback. Keep established grid/ICC/EXIF/gain-map validation behavior when integrating any official encoder route.
