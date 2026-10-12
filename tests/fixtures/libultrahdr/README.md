# libultrahdr regression fixtures

These unmodified test images are from [google/libultrahdr](https://github.com/google/libultrahdr), release v2.0.2, commit `e5f5a022fe96fc4dc2ee35c19f733a50df807abe`:

- [apple_gainmap_new.jpg](https://github.com/google/libultrahdr/blob/e5f5a022fe96fc4dc2ee35c19f733a50df807abe/tests/data/apple_gainmap_new.jpg): Apple gain-map JPEG with EXIF/ICC, used by HDR preservation, preview and Motion Photo regressions.
- [minnie-320x240-yuv-icc.jpg](https://github.com/google/libultrahdr/blob/e5f5a022fe96fc4dc2ee35c19f733a50df807abe/tests/data/minnie-320x240-yuv-icc.jpg): SDR JPEG with ICC, used by color-profile and storage regressions.

Attribution: the libultrahdr project and the upstream sample contributors. Upstream distributes this test-data directory under [CC-BY-4.0](LICENSE); the original license text is retained. These images are not relicensed under UltraSlim's GPLv3. No sample pixels or metadata have been changed.

`SHA256SUMS` records all three upstream files, checked against the pinned raw GitHub files during repository preparation. `python3 scripts/check_repository.py` verifies local bytes. Only the two fixtures actually needed by UltraSlim are included; other upstream examples and locally produced outputs are excluded.
