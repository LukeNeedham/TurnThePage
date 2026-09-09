# Архитектурные решения этапов 0–2

## Границы слоёв

Поток данных будущего Reader фиксируется в одном направлении:

```text
Book source → parser → BookDocument → pagination → PageLayout
            → PageSurface → BookRenderer → Compose adapter
```

- `:core:model` — чистый Kotlin, без Android, Compose и FB2.
- `:format:fb2` зависит только от внутренней модели.
- `:reader:pagination` не знает об XML и UI.
- `:reader:rendering` не знает о формате исходного документа.
- Compose используется на границе feature/app и позднее в adapter renderer-а.
- Пользовательские строки принадлежат UI-модулям и локализуются на границе UI.

## Зафиксированные продуктовые решения

- `minSdk = 26`, `targetSdk = compileSdk = 36`.
- Этапы 0–2 образуют официальный графический MVP.
- Исходная книга на этапе импорта будет копироваться в private app storage.
- Полное закрытие передней и задней обложки входит в v1.
- Верхний corner curl и ZIP с несколькими FB2 не входят в v1.
- Backup и telemetry отключены; разрешение `INTERNET` не требуется.

## Stage 1 BookView

`PageCurlGeometryEngine` в `:reader:rendering` не зависит от Android и Compose.
Для нижнего внешнего угла, точки на внешнем крае и произвольного anchor он вычисляет
constrained pointer, перпендикулярный сгиб, две half-plane области страницы,
отражённый оборот и affine-преобразования между исходной, текущей и конечной
поверхностями. Вертикальное отклонение edge/corner ограничено относительно пройденного
по горизонтали расстояния, чтобы лист оставался в допустимой проекционной области.
Ввод с NaN/Infinity, вырожденная геометрия и near-zero знаменатели обрабатываются
безопасно.

`PageCurlBandGeometryEngine` заменяет жёсткий шарнир узкой piecewise-affine зоной
из 8–12 полос. Полосы и жёсткий остаток без перекрытий покрывают поднятую часть
листа, непрерывно стыкуются с обычным reflection и исчезают на обоих плоских
endpoint. Модель остаётся чистым Kotlin и зеркальна для forward/backward.

Compose-адаптер в `:feature:reader`:

- заранее измеряет все 32 demo `PageSurface`, поэтому drag не запускает text layout;
- рисует обложку, бумагу, стопки, корешок и curl в одном Canvas;
- принимает drag с любой точки листа, проецирует material grab на внешний край той
  же высоты и сохраняет finger offset, поэтому первый кадр не прыгает;
- рисует подготовленный контент оборота через `currentReflection ∘ finalReflection`
  с первого ненулевого кадра — без порога появления и cross-fade;
- в single-page режиме рисует слабый зеркальный show-through текущей стороны, а
  целевую страницу оставляет физически под листом;
- выбирает COMPLETE/CANCEL по расстоянию и velocity через `PageTurnPhysicsConfig`;
- анимирует только settling после отпускания или edge tap;
- предоставляет зеркальные forward/backward жесты и accessibility actions.

## Stage 2 Adaptive/Fold

### Граница AndroidX Window

`:core:adaptive` — единственное место, где Reader зависит от AndroidX Window
`1.5.1`. `WindowManagerDevicePostureProvider` получает события из
`WindowInfoTracker.windowLayoutInfo(activity)` только внутри
`repeatOnLifecycle(STARTED)` и публикует `StateFlow<DevicePosture>`. Начальное
состояние и результат восстанавливаемой ошибки — `Unknown`; отмена coroutine не
поглощается.

Публичная модель не содержит Compose и vendor API: `FoldBounds`,
`FoldOrientation`, `DevicePosture.Unknown`, `Flat` и `HalfOpened`. Если WindowManager
сообщает несколько `FoldingFeature`, чистый mapper выбирает один детерминированно:
сначала separating, затем occluding, half-opened, vertical и ближайший к центру
окна. Samsung-специфичный API не используется.

`MainActivity` владеет provider-ом и передаёт posture вниз как состояние. Модуль
`:feature:reader` переводит нейтральную модель в собственный `BookDisplayFeature`;
curl-движок по-прежнему ничего не знает об AndroidX Window.

### Фактические размеры и система координат

Решение не опирается на имя устройства, orientation или фиксированный профиль:

1. `WindowMetricsCalculator` даёт текущий размер окна для выбора релевантного
   `FoldingFeature`.
2. `BoxWithConstraints` и `LocalDensity` дают фактический доступный viewport и
   density ридера после системных/навигационных insets.
3. `boundsInWindow()` фиксирует положение viewport в окне.
4. Fold/hinge bounds обрезаются по viewport и переводятся в локальные координаты
   Canvas. Нулевая ширина fold-линии при этом сохраняется.

Поэтому split-screen, изменение размера окна и off-center hinge проходят через ту
же геометрию, что и обычный fullscreen, без отдельных device presets.

### Политика single/spread и hinge-safe layout

В automatic-режиме действуют следующие правила:

| Состояние viewport | Представление и размещение |
| --- | --- |
| Нет вертикального fold/hinge | Spread только при полезной ширине не менее `720 dp` и полезной ширине каждого листа не менее `320 dp`; иначе single. Высота и пропорция листа `0.675` также участвуют в проверке. |
| Вертикальный fold/hinge | Левая и правая панели измеряются отдельно. Spread разрешён, только если на каждой помещается лист шириной не менее `320 dp`; общий breakpoint `720 dp` здесь не нужен. |
| Separating/occluding вертикальный hinge | Левый лист заканчивается у левой границы hinge, правый начинается у правой; вся ширина недоступной области исключена, а физический корешок совмещён с hinge. |
| Горизонтальный separating/occluding hinge или horizontal half-opened posture | Только single; лист помещается целиком в безопасную панель. При одинаковой ёмкости выбирается верхняя панель. |
| Вертикальный hinge, для которого две полноценные страницы не помещаются | Single в панели с большей полезной ёмкостью; при равенстве выбирается правая. |

Off-center вертикальный hinge становится реальным центром книги, а оба листа
получают одинаковый размер, ограниченный меньшей панелью. Небезопасная область не
перекрывает бумагу и текст. Явный `forcedPresentation` оставлен только для
preview/screenshot/test сценариев; продуктовый путь использует `AUTOMATIC`.

### Resize и смысловая позиция

`currentIndex` хранит смысловой лист, а не левую границу текущего layout. При
переходе в spread `DemoPageNavigator.layoutAnchorIndex` вычисляет канонический
разворот, содержащий этот лист: титульный лист `0` остаётся одиночным, последующие
развороты начинаются с нечётного индекса. При возврате в single снова используется
исходный смысловой индекс, поэтому последовательность narrow → wide → narrow не
сдвигает читателя на соседнюю страницу.

Изменение layout отменяет незавершённый drag/settle, чтобы старая геометрия не
закончила перелистывание уже в новом окне, затем повторно измеряет `PageSurface` под
новый размер. Для demo позиция проверяется стабильным индексом листа; после
подключения настоящей пагинации тем же якорем должна стать логическая позиция в
документе, а не номер заново рассчитанной страницы.

### Проверка и ограничения

- JVM-тесты покрывают валидацию bounds, flat/half-opened/occluding mapping,
  детерминированный выбор feature, dp-пороги, off-center и zero-width folds,
  window-to-viewport преобразование, hinge-safe layout и повторный resize.
- Screenshot-тесты проверяют single/spread и физический curl на синтетических
  viewport, но не являются проверкой OEM WindowManager implementation.
- Compose instrumentation покрывает portrait/landscape single/spread жесты и
  собирается отдельным test APK.
- В текущем окружении нет подключённого ADB-устройства или Android Emulator,
  поэтому `connectedDebugAndroidTest`, реальные hinge bounds и переходы
  flat ↔ half-opened на Fold не запускались. Для аппаратной приёмки нужен ручной
  smoke-test на одном Fold в flat, book и tabletop posture, включая resize/split-screen.
