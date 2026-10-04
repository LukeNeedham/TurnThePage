# Листай 1.1 в F-Droid

28 сентября 2026 года отправлена заявка
[New app: Листай (Turn the Page), !50461](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50461).
Она открыта для проверки сопровождающими. Это ещё не публикация приложения
в основном каталоге F-Droid.

## Исходники и рецепт

- Приложение: `com.avstaim.listai`, версия `1.1`, код `15`, Android API 26–36.
- Публичный Git: `https://git.sourcecraft.dev/avstaim/listai.git`.
- Коммит сборки: `fefeb767880f40aa1b74248bc58b77ba7def2435`.
- [Рецепт сборки](fdroid/com.avstaim.listai.yml) совпадает с отправленным в F-Droid.
- [Лицензии](licenses.md): Apache-2.0 для кода, CC-BY-4.0 для собственной книги
  и иллюстраций; сторонние материалы сохраняют свои лицензии.
- Fastlane RU/EN находится в `fastlane/metadata/android/`: описание, иконка,
  скриншоты и changelog `15.txt`. Скриншоты унаследованы из проверенной карточки 1.0;
  новые кадры в рамках подготовки F-Droid не создавались.

Коммит после релиза добавляет лицензии, уведомления в APK, Fastlane metadata
и полную оптимизацию R8 кода и ресурсов по просьбе сопровождающего linsui.
Опубликованный тег `v1.1` остаётся на прежнем коммите. Для последующих выпусков
настроено получение версии из `gradle.properties` и обновление по тегам `v*`.

## Проверка

### Первоначальная заявка

Для исходного коммита `88d8b70d785f868c1d747bba1eb6d3fee90d84f1` прошли `fdroid lint`, `fdroid rewritemeta` и настоящий локальный
`fdroid build --test --verbose --stop com.avstaim.listai:15` с fdroidserver 2.4.5,
официальным gradlew-fdroid, JDK 21, Gradle 9.5.1 и SDK 36.
`fdroid checkupdates --allow-dirty com.avstaim.listai` также прошёл: теги и
`UpdateCheckData` правильно определяют `1.1` / `15`. `AutoName` нормализован
к стандартному английскому названию `Turn the Page`; русское название «Листай»
сохраняется в Fastlane.

F-Droid самостоятельно получил публичные исходники, выбрал указанный коммит,
удалил настройки подписи и Gradle wrapper, выполнил сканирование исходников,
`clean` и `assembleRelease` (346 задач), проверил пакет и версию, затем выполнил
сканирование APK. Проверки не отключались. Локально также прошли
`:app:assembleRelease lintRelease`; наличие полных лицензионных текстов в APK
проверено побайтовым сравнением.

SHA-256 локального неподписанного APK:
`53c68682eed742527bb1a37d117549f7cccee3b4231e1f0bbd55a40c39fa30b6`.

Это локальная сборка с существующим кэшем зависимостей, а не официальная
виртуальная машина F-Droid. [Pipeline fork](https://gitlab.com/avstaim/fdroiddata/-/pipelines/2889303602)
GitLab был остановлен требованием проверки аккаунта до запуска задач.

28 сентября сопровождающий linsui запустил
[pipeline F-Droid #2889553039](https://gitlab.com/fdroid/fdroiddata/-/pipelines/2889553039).
Прошли 8 из 9 заданий, включая `fdroid build`, проверки исходников и APK,
`fdroid lint`, `fdroid rewritemeta` и `checkupdates`. Единственная ошибка —
проверка JSON Schema для `AutoUpdateMode: Version v%v`.

29 сентября параметр исправлен на `AutoUpdateMode: Version`: при проверке
по тегам F-Droid использует найденный тег напрямую, включая префикс `v`.
Коммит исходников сборки 1.1 и опубликованный тег не изменены.
Исправление отправлено в заявку коммитом
[`c753e8ac`](https://gitlab.com/avstaim/fdroiddata/-/commit/c753e8ac5b3c5b25534fb8c34743914d1592a764).
Полная проверка JSON Schema из упавшего pipeline и текущей `master`
воспроизводит ошибку старого значения и принимает исправленное.
Также повторно прошли `fdroid lint` и `fdroid rewritemeta --list`.
После исправления сопровождающим удаления Gradle wrapper прошли все 9 заданий
[pipeline F-Droid #2892671505](https://gitlab.com/fdroid/fdroiddata/-/pipelines/2892671505)
на коммите рецепта `6ce41ba7fe843d11abeca85eeeb26c4124d77628`.

### R8, 4 октября 2026

По [просьбе linsui](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50461#note_3946449241)
включены `isMinifyEnabled`, `isShrinkResources` и R8 full mode с
`proguard-android-optimize.txt`. AGP 9.2.1 также включает optimized resource
shrinking. Собственные широкие keep/dontwarn не добавлялись. Изменение перенесено
на исходники 1.1 отдельным коммитом `fefeb767880f40aa1b74248bc58b77ba7def2435`;
основная ветка сохраняет эту настройку для будущих выпусков.

Чистые `:app:assembleRelease`, `:app:assembleBenchmark` и `:app:test` прошли.
Неподписанный APK уменьшился с примерно 12,1 МБ до 4,1 МБ.
Пакет, версия 1.1 / код 15 и опубликованный тег не менялись.

## Подпись

Рецепт предусматривает подпись F-Droid. Подпись разработчика из SourceCraft
и RuStore не переносится: опубликованный APK 1.1 не прошёл сравнение
`apksigcopier` с чистой сборкой. Различаются сгенерированный Compose-код
в `classes2.dex`, его контрольная сумма в `baseline.prof` и VCS metadata.

Поэтому установки из F-Droid и SourceCraft/RuStore имеют разные сертификаты
и не обновляют друг друга поверх существующей установки. Исходные файлы книг
следует сохранять отдельно; при удалении приложения теряются его локальные
библиотека, прогресс и закладки. Переход F-Droid на другой сертификат позднее
не предусмотрен этим рецептом.

После принятия merge request сопровождающие F-Droid выполнят свою сборку,
подпись и публикацию. Текущий статус следует проверять по ссылке на заявку.
