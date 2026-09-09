# Листай / Turn the Page

Android-читалка с физически правдоподобной моделью настоящей книги.

## Текущее состояние

Реализованы этапы 0–4 — технический фундамент, физический `BookView`,
адаптация к размеру окна/Fold posture, FB2 pipeline и настоящая пагинация:

- Kotlin и Jetpack Compose;
- `applicationId = com.avstaim.listai`;
- Android API 26–36;
- английская default-локализация и русская локализация;
- debug pseudolocales;
- модульные границы parser → model → pagination → rendering → UI;
- fake `BookDocument` на 32 детерминированных блока;
- единый Canvas `BookView` без Pager;
- single page и two-page spread с мягким корешком;
- чистая бумага без синтетической «грязи», мягкое освещение и толщина стопок;
- независимая геометрия curl для forward/backward с lower-corner, edge и free anchors;
- захват с любой точки листа, возврат жеста, flick/tap completion и settle-анимации;
- 12-полосная curl-band, отдельные cast/contact shadows и подготовленные PageSurface;
- контент оборота приклеен affine-преобразованием к листу с первого кадра, без fade;
- `:core:adaptive` с AndroidX Window `1.5.1`, нейтральной моделью posture и
  lifecycle-aware `WindowInfoTracker` provider;
- автоматический single/spread по фактическим constraints, density и fold/hinge bounds;
- hinge-safe размещение: корешок совпадает с вертикальным hinge, а бумага не попадает
  под separating/occluding область;
- resize и переход single ↔ spread сохраняют текущий смысловой лист;
- unit, Compose instrumentation, screenshot и macrobenchmark scaffolds;
- CI для build/test/lint/golden validation;
- потоковый namespace-aware SAX parser для `.fb2` и `.fb2.zip`;
- формат-независимый `BookDocument` с метаданными, семантическими блоками,
  иерархическим оглавлением, сносками, anchors и типизированными ссылками;
- отложенное декодирование FB2 `<binary>` с кешированием результата;
- ограничения размера XML/ZIP/binary, запрет DTD/external entities и типизированные ошибки;
- встроенный FB2 fixture и просмотр разобранного потока на экране «Диагностика»;
- детерминированная инкрементальная пагинация на фоновом dispatcher;
- переносы строк/слов, абзацные отступы, заголовки, rich text, стихи, цитаты и изображения;
- сохранение `BookLocation` при fullscreen, resize, повороте и single ↔ spread;
- реальный русский FB2 «Атлас тихих страниц» в основном ридере.

Жест можно начать в любой точке страницы: вправо/влево выбирается направление,
а внешний край следует за точным смещением пальца без стартового скачка.
Верхний corner curl согласно границам v1 не реализуется. Основной ридер получает
настоящие `PageLayout`; демо сохранено для регрессионных тестов графики.
Кеш/eviction поверхностей относятся к этапу 5. Room, SAF и копирование
исходника в private app storage относятся к этапу 6.

## Команды

Для сборки нужны Android Studio или Android SDK с API 36 и JDK 17 или новее.
После клонирования репозитория запустите из его корня:

```bash
./gradlew assembleDebug
```

Debug APK появится в `app/build/outputs/apk/debug/`.

Основные проверки проекта:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
./gradlew :core:adaptive:test
./gradlew :reader:pagination:test
./gradlew lintRelease
./gradlew :feature:reader:validateDebugScreenshotTest
./gradlew :benchmark:assemble
```

Instrumentation tests требуют запущенного устройства или эмулятора:

```bash
./gradlew connectedDebugAndroidTest
```

Обновление screenshot-эталонов выполняется только после визуальной проверки:

```bash
./gradlew :feature:reader:updateDebugScreenshotTest
```

## Архитектура

```text
:format:fb2 ──► :core:model ──► :reader:pagination ──► :reader:rendering
                                                           │
                                                           ▼
                                                   :feature:reader
                                                           │
:core:files ─┬─► :feature:library                          │
:core:database┘             └───────────────────────────────┴─► :app
:core:adaptive ───────────────────────► :feature:reader ─────┘
```

`:app` разбирает встроенный FB2 и передаёт `BookDocument` в `ReaderScreen`.
`:reader:pagination` остаётся чистым Kotlin; Android `StaticLayout` и декодирование
изображений изолированы адаптером `BlockMeasurer` в `:feature:reader`.

`BookDocument` содержит исходные смысловые блоки, но не страницы. Границы страниц
принадлежат `:reader:pagination`. Mapper fake-блоков в демо-страницы используется
только старым графическим демо и его регрессионными тестами.

Чистая curl-геометрия находится в `:reader:rendering`: она принимает положение
пальца и возвращает constrained pointer, линию сгиба, лицевой остаток страницы,
отражённый оборот, affine-преобразования контента и нормализованную высоту листа.
`PageCurlBandGeometryEngine` разбивает узкую область сгиба на 12 непрерывных полос.
Compose отвечает только за ввод, settle-анимацию и Canvas-отрисовку.

`:core:adaptive` изолирует AndroidX Window от ридера. `MainActivity` создаёт
`WindowManagerDevicePostureProvider`, а `:feature:reader` получает только
`DevicePosture`. Размер книги рассчитывается по фактическому Compose viewport;
fold/hinge переводится из координат окна в локальные координаты Canvas и участвует
в выборе single/spread и безопасном размещении листов.
