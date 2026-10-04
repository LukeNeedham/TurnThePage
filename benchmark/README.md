# Reader benchmarks

The benchmark APK targets the app's `benchmark` variant: release behavior,
non-debuggable, shell-profileable, signed with the local debug key. The test APK
itself remains debuggable so Android can run its instrumentation. No production
signing credentials are required. The test APK is self-instrumenting: its runner
and private fixture provider live in `com.avstaim.listai.benchmark`, separately
from the measured `com.avstaim.listai` process.

## Emulator run

Use one selected portrait-natural phone emulator with a portrait width below 720 dp and a landscape
width above 728 dp, with at least 248 dp of height. The spread test checks that
every turn advances exactly two source pages; it will fail on a window too narrow
for automatic spread presentation instead of silently measuring single pages.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

`EMULATOR` is the only suppressed macrobenchmark environment warning. Do not
suppress `DEBUGGABLE` or `NOT-PROFILEABLE`; those indicate the wrong target build.
All timings are emulator observations, not physical-device smoothness claims.

The suite includes:

- `MinifiedReleaseSmokeTest.importsTurnsAndRestoresInOptimizedApp`: an external
  functional smoke test of the optimized, non-debuggable app. It imports FB2,
  EPUB and PDF through the shipping content-URI entry point, turns pages in both
  directions, and verifies the saved page after force-stop and cold launch.
  A malformed PDF immediately precedes the valid PDF to exercise the API 26/27
  isolated parser service. It reuses the licensed fixtures from the app's test
  assets and saves screenshots/hierarchy in the benchmark APK's external
  `release-smoke` directory. It requires no macrobenchmark warning suppression.
- `StartupBenchmark.coldStartupIntoImportedBook`: five cold launches with no
  precompilation. `StartupTimingMetric` measures the initial displayed frame.
  The measured block also waits for actual imported source ink, a completed page
  total, and an enabled turn. This readiness check is not a separate fully-drawn
  timing metric because the application does not report `reportFullyDrawn`.
- `PageTurnBenchmark.repeatedSinglePageTurns`: five AOT-compiled iterations,
  each walking six pages forward and six backward in portrait.
- `PageTurnBenchmark.repeatedSpreadPageTurns`: the same flow in landscape,
  advancing two source pages per turn. The synthetic contents leaf precedes
  source pages 1 and 2, so the source opens on a complete spread.

`ReaderBenchmarkFixture` generates a fixed FB2 corpus, grants a scoped read-only
content URI, and opens it through the shipping `ACTION_VIEW` importer. Single and
spread cases use separate deterministic books. Imports are deduplicated without
clearing application data. Bounds come from the accessible book node. Every
swipe must publish its expected source page before the next swipe; there are no
fixed animation sleeps. Each measured walk must return to its starting page.
Setup waits for two identical completed source snapshots in the requested
orientation and presentation. Presentation is checked against the actual
published leaf texts: this controlled corpus contains no internal blank lines,
whereas the reader separates two visible leaves with a blank line. Absolute
natural/left rotations avoid carrying stale display dimensions between tests.
Readiness failures include the active root package, visible text, and last
observed accessible page state, and save
`reader-readiness-failure.xml` in the benchmark APK's external files directory.

Gradle writes test results and reports below `benchmark/build/outputs/` and
`benchmark/build/reports/`. Retain the macrobenchmark JSON and each generated
Perfetto trace, together with emulator API, resolution/density, renderer, target
version, build variant, and the exact command. Inspect frame-duration/overrun
percentiles and trace scheduling before drawing conclusions from a single spike.

## Functional stress evidence

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.avstaim.listai.Stage9ReaderStressTest
adb -s emulator-5554 pull \
  /sdcard/Android/data/com.avstaim.listai/files/stage9/reader-stress-report.txt \
  ./reader-stress-report.txt
```

`Stage9ReaderStressTest` runs 100 completed turns, six full-note visits, two
layout/font reflows during notes, and two activity background/resume cycles.
It verifies exact source returns, unchanged checkpoints during notes and drags,
reused drag rasters, a 64 MiB surface-cache budget, a bounded raster count per
walk, and no more than six pinned surfaces after memory trimming. The report
includes sampled PSS and cache/raster counts; PSS has no brittle emulator threshold
and is not a leak verdict. The `completedModes=2` trailer proves the full run
finished. A partial report remains on failure for diagnosis.

Use the macrobenchmark target for frame/Perfetto measurements. The Compose
instrumentation stress run uses a debug APK to inspect semantic checkpoints and
cache state; its wall-clock duration is not a release performance measurement.

`Stage9RasterRetentionTest` in `:feature:reader` additionally drives the real
Android rasterizer and serialized surface store directly. It walks 40 distant
windows per layout until eviction occurs, verifies that 120 repeated warm-frame
requests create no rasters, retains only the pinned window after trimming, and
checks that closing a session drops cache/scene ownership without recycling a
bitmap still held by an immutable scene. This is bounded-ownership evidence;
it does not replace heap analysis for a leak claim.
