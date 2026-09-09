# Листай / Turn the Page

Android-читалка с физически правдоподобной моделью настоящей книги.

## Текущее состояние

Завершены этапы 0–2 — технический фундамент, интерактивный `BookView Demo`
и адаптация к реальному размеру окна/Fold posture:

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
- CI для build/test/lint/golden validation.

Жест можно начать в любой точке страницы: вправо/влево выбирается направление,
а внешний край следует за точным смещением пальца без стартового скачка.
Верхний corner curl согласно границам v1 не реализуется. FB2 parsing, Room и SAF
относятся к следующим этапам.

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

`BookDocument` содержит исходные смысловые блоки, но не страницы. Границы страниц
принадлежат `:reader:pagination`. Временный mapper fake-блоков в демо-страницы
находится только в `:feature:reader` и будет удалён при подключении настоящей
пагинации.

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
