---
name: listai-books
description: Implement, review, or verify Листай’s FB2 parsing, source anchors, private-book storage, Room migrations, and durable reading state. Use for books and persistence work; use listai-reader-ui for Compose reader presentation.
metadata:
  short-description: FB2, book storage, anchors, and persistence
---

# Листай books and persistence

Use this skill for `format/fb2`, `core/model`, `core/files`, and
`core/database` changes. Read the relevant sections of
[`docs/architecture.md`](../../../docs/architecture.md), especially Stage 3
(model/parser), Stage 4 (pagination contracts), and the persistence sections,
before changing a data contract.

## Parsing and source identity

- Keep `BookDocument` format-independent and the parser namespace-aware and
  streaming. Enforce the existing XML/ZIP/binary limits, reject DTD and
  external entities, and represent malformed input with the project’s typed
  parse errors. Do not introduce a DOM or silently relax a limit.
- Treat `BookLocation.charOffset` and `fallbackGlobalOffset` as Kotlin UTF-16
  source offsets. Preserve stable block IDs and resolve links/TOC after the
  complete SAX pass; notes are not ordinary reading-flow pages.
- Keep the primary text anchor, TOC anchor, annotation/frontmatter anchor, and
  note-visit anchor independent. Annotation progress must not replace the
  reader’s main source progress or alter its fraction.

## Import and storage

- Import creates a private app-owned copy while retaining the user’s original
  file. Deduplication must preserve the existing `BookId` and progress rather
  than reset a matching book.
- Use the existing private-storage/repository transaction flow: failures,
  cancellation, malformed input, and interrupted publication must not leave a
  partial published copy or an orphaned database row.
- Deletion removes the app-owned copy and associated durable rows only; never
  delete or mutate the external original supplied by the user.

## Room and durable state

- Room migrations are additive and non-destructive. For schema v3, retain books,
  bookmarks, source progress, TOC state, and the independent annotation columns
  across v1/v2 upgrade paths. Never use destructive fallback to recover from a
  missing migration.
- Keep persistence models and model conversion aligned: nullable annotation
  locations remain nullable, while their character/global offsets use the
  established defaults. Validate both a real close/reopen and a live DAO read.
- Keep DataStore reader preferences separate from library rows and preserve
  unrelated durable state when one selection or book is removed.

## Verification

Run focused tests before a full build:

```bash
./gradlew :format:fb2:test
./gradlew :core:files:test
./gradlew :core:database:test
./gradlew :core:database:assembleDebugAndroidTest
```

When an emulator is available, run the affected database/import instrumentation
tests on API 26 and API 36. Include raw FB2, ZIP, hostile/oversized input,
UTF-16 or legacy encodings, cancellation/rollback, deduplication, close/reopen,
bookmarks, and v1/v2→v3 migrations. Keep JDK 17+ compatibility in local
development and follow the project’s CI JDK when reproducing CI-only behavior.

Publishing, signing, and SourceCraft mutations are outside this skill; use the
existing authorized `listai-release` workflow only when the user explicitly
requests a release action.
