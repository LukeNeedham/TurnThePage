# Визуальные материалы RC1

Все изображения показывают компоненты Листай, без сторонних книжных обложек.
Copyright 2026 Алексей Щербинин. Изображения доступны по
[CC-BY-4.0](../../LICENSES/CC-BY-4.0.txt); атрибуция и исключения —
в [перечне лицензий](../licenses.md).

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
