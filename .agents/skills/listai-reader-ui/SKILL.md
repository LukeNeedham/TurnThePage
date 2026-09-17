---
name: listai-reader-ui
description: Implement, review, or verify Листай’s physical Reader UI, Compose book surfaces, curl geometry integration, and paper controls across phone and Fold layouts. Use for feature/reader UI work; use listai-books for parser, storage, or persistence changes.
metadata:
  short-description: Physical Reader UI, curl, and paper controls
---

# Листай Reader UI

Use this skill at the `feature/reader` and `reader/rendering` boundary. Read the
relevant sections of [`docs/architecture.md`](../../../docs/architecture.md)
before changing geometry, measurement, or source-position behavior; especially
the Stage 1, Stage 2, Stage 4, and Stage 7 sections.

## Production path

- Treat `feature/reader/src/main/kotlin/com/avstaim/listai/feature/reader/ReaderDemoScreen.kt`
  (which hosts the production `ReaderScreen`) and `feature/reader/bookview/` as
  the production path. The `ReaderDemoScreen` composable and its fixtures are
  for graphics/demo regression coverage, not a replacement for the real
  paginated reader.
- Keep curl math in `reader/rendering` as pure Kotlin. Compose owns gestures,
  settling, lifecycle, and Canvas composition; Android text measurement and
  raster preparation stay in the reader adapter.
- Preserve `BookLocation` and source offsets through pagination, navigation,
  resize, single/spread changes, annotation visits, TOC visits, and return to
  the shelf. A recalculated page number is not a durable reading anchor.
- Prepare text and image surfaces before a drag. Do not perform text layout or
  image decoding on curl frames, and keep the turning content attached from the
  first non-zero frame without a fade.

## Layout and controls

- Base decisions on actual Compose constraints, density, insets, and
  `DevicePosture`; do not branch on device names or orientation presets.
- Keep separating/occluding hinges out of paper and text. In spread mode the
  physical spine follows the safe hinge boundary; fall back to single when two
  complete pages do not fit.
- Keep the paper insert and top controls inside the safe viewport, including
  cutouts. The whole-viewport menu and top-menu action must resolve to the same
  menu without stealing a page gesture or covering book text.
- Preserve the cold blue/grey UI, clean white or graphite paper, rigid cover,
  and restrained shadows. Avoid persistent toolbars or diagnostic overlays.
- Validate both light/dark themes and RU/EN strings at normal and enlarged
  font scale. Check single and spread, portrait/landscape, narrow windows, and
  Fold hinge-safe placement.

## Verification

Run the narrowest relevant checks first:

```bash
./gradlew :reader:rendering:test
./gradlew :feature:reader:testDebugUnitTest
./gradlew :feature:reader:validateDebugScreenshotTest
./gradlew :feature:reader:assembleDebugAndroidTest
```

For a visual change, inspect the rendered result before updating references;
goldens are evidence of an intentional change, not a way to silence a diff.
When an emulator is available, run the affected `connectedDebugAndroidTest`
scenario on API 26 and API 36, including both themes and the relevant phone or
Fold layout. Report unavailable device coverage instead of implying hardware
FPS guarantees.
