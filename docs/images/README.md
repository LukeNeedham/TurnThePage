# Визуальные материалы

Все изображения показывают компоненты Листай, без сторонних книжных обложек.
Copyright 2026 Алексей Щербинин. Изображения доступны по
[CC-BY-4.0](../../LICENSES/CC-BY-4.0.txt); атрибуция и исключения —
в [перечне лицензий](../licenses.md).

## Версия 1.5

Снимки для актуального README обновлены 2026-10-02. Версия приложения —
`1.5`, `versionCode = 28`; исходный коммит — `9e282a9`.
Изображения скопированы без ретуши, изменения размера или обрезки.

- `v1.5-reading.png`, `v1.5-title.png`, `v1.5-spread.png`, `v1.5-dark.png`,
  `v1.5-contents.png` — свежие снимки Android API 36 emulator, production
  `MainActivity`, встроенный рассказ `app/src/main/res/raw/stage4_reading_sample.fb2`.
  Телефон: 1080×1920 px / 420 dpi; широкое окно: 2208×1840 px / 420 dpi.
  Широкое окно показывает автоматический разворот, а не аппаратный сгиб Fold.
- `v1.5-shelf.png` — `Rc5PrototypeShelfScreenshot`, состояние
  «Прототип — на полке»; `v1.5-stack.png` — `Rc5PrototypeOpenStackScreenshot`,
  состояние «Прототип — внутри стопки». Оба кадра взяты из проверенных эталонов
  `feature/library/src/screenshotTestDebug/reference/` и показывают production
  `LibraryScreen` с воспроизводимой библиотекой. Слово «Прототип» — имя тестового
  сценария соответствия согласованному дизайну.
- `v1.5-search.png` — `V12LibrarySearchScreenshot`, состояние
  «Search — books inside stacks», из того же набора эталонов. Английский интерфейс,
  поиск по автору находит книги из разных стопок.
- `v1.5-illustration.png` — `BookImageViewerFitScreenshots`, состояние
  «Иллюстрация — весь экран ru», из
  `feature/reader/src/screenshotTestDebug/reference/`. Production
  `BookImageViewerContent` и собственная тестовая иллюстрация обсерватории.

Это снимки и рендеры действующих компонентов приложения. Эталонные кадры
проверены проектным screenshot gate на исходном коммите; для документации
эталоны не менялись.

## Исторические материалы RC1

- `rc1-reading.png`, `rc1-spread.png`, `rc1-dark.png`, `rc1-contents.png` —
  Android API 36 emulator, production `MainActivity`; встроенный оригинальный
  рассказ `app/src/main/res/raw/stage4_reading_sample.fb2`. Широкое окно
  1800×1600 px / 240 dpi демонстрирует automatic spread, а не аппаратный hinge.
- `rc1-shelf.png` — `LibraryRussianScreenshot` из screenshot-тестов;
  production `LibraryScreen` и собственные демонстрационные обложки.
- `rc1-notes.png` — `Stage8RussianNotesSectionScreenshot`;
  production renderer, собственный тестовый текст и действующая печатная ссылка возврата.

Это реальные рендеры приложения, не дизайн-макеты. Скриншоты fixtures нужны для
воспроизводимого заполненного шкафа и примечаний, отсутствующих во встроенном рассказе.
Обновлять их только после визуальной проверки соответствующего состояния.
