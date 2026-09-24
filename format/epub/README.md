# EPUB import

`EpubBookParser` implements the shared `BookParser` boundary and produces the existing
format-independent `BookDocument`. It follows package spine order and accepts EPUB 2
NCX and EPUB 3 navigation documents. Specification: [EPUB 3.3](https://www.w3.org/TR/epub-33/).

Supported content includes metadata and cover images, paragraphs/headings, inline
emphasis, bold/strikethrough, raster images, hyperlinks, and semantic EPUB footnotes.
Basic class/ID/tag selectors in linked or embedded CSS retain text emphasis. Reader
preferences control page layout and type size; publication fonts, complex CSS layout,
JavaScript, remote resources, multimedia and DRM are not rendered.

Source IDs combine the normalized archive path and escaped fragment ID. Character and
fallback offsets use UTF-16, notes remain outside the main reading flow, and SHA-256 of
the original archive identifies repeated imports independently of the supplied filename.

ZIP input is copied to a bounded temporary file, never extracted into a user directory.
Archive sizes, compression ratios, resource counts, XML depth/characters, stylesheet
bytes and image bytes have independent ceilings. SAX is namespace aware and rejects
DTD/external entities in the decoded stream, including UTF-16 input. This deliberately
rejects legacy EPUB files that require external XHTML DTDs. Image payloads are kept
compressed until first load and do not depend on the lifetime of the temporary ZIP.

Run `./gradlew :format:epub:test` for package/spine/navigation, note and UTF-16 anchor,
image, stylesheet, deduplication and hostile input coverage.
