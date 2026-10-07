# Листай в F-Droid

Листай опубликован в [основном каталоге F-Droid](https://f-droid.org/packages/com.avstaim.listai/).
Версия `1.1` / код `15` доступна с 6 октября 2026 года. Первоначальная заявка
[New app: Листай (Turn the Page), !50461](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50461)
принята 5 октября после успешного
[официального pipeline #2910777765](https://gitlab.com/fdroid/fdroiddata/-/pipelines/2910777765).

## Подготовка обновления 1.5

[Рецепт сборки](fdroid/com.avstaim.listai.yml) сохраняет опубликованную `1.1` и
добавляет `1.5` / код `28`, Android API 26–36, из неизменяемого тега `v1.5`.
Полная оптимизация R8 кода и ресурсов обязательна и для сборки F-Droid.
Fastlane RU/EN в `fastlane/metadata/android/` обновлён: описание возможностей,
проверенные скриншоты 1.5 и changelog `28.txt`.

Обновление рецепта отправляется в `fdroid/fdroiddata` после публикации тега.
Для последующих выпусков включены поиск версий по тегам `v*` и получение
`versionName` / `versionCode` из `gradle.properties`. Новый APK собирают,
подписывают и публикуют сопровождающие F-Droid; загрузка разработчиком APK
из SourceCraft или RuStore для этого канала не требуется.

До появления `1.5` в каталоге доступной версией F-Droid остаётся `1.1`.

## История первоначальной заявки 1.1

## Исходники и рецепт

- Приложение: `com.avstaim.listai`, версия `1.1`, код `15`, Android API 26–36.
- Публичный Git: `https://git.sourcecraft.dev/avstaim/listai.git`.
- Коммит сборки: `fefeb767880f40aa1b74248bc58b77ba7def2435`.
- Рецепт `1.1` принят в F-Droid; следующая сборка добавляется отдельным блоком.
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

Повторный полный локальный `fdroid build --test --verbose --stop
com.avstaim.listai:15` с fdroidserver 2.4.5 прошёл: 345 задач, source scanner
и binary scanner без отключения проверок. Lint, rewritemeta и JSON Schema
также прошли. Неподписанный APK — 4 109 707 байт, SHA-256:
`fcca38a40fe0e3fd685034da80b09d864ef09e69e7a32ce355b9565d86312321`.
Это native local build, а не официальная VM F-Droid.

Минифицированный benchmark-вариант 1.1 прошёл внешний тест на API 26 и 36:
импорт FB2/EPUB/PDF, перелистывание в обе стороны, сохранение страницы после
остановки процесса и холодного запуска, успешный импорт PDF после повреждённого.

Рецепт обновлён коммитом
[`f1d20961`](https://gitlab.com/avstaim/fdroiddata/-/commit/f1d209618751f567680caa13c0954eb5f96db4c3).
[Ответ linsui](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50461#note_3950924814)
содержит результаты и запрос повторного CI. Новый
[fork pipeline #2910592981](https://gitlab.com/avstaim/fdroiddata/-/pipelines/2910592981)
остановлен GitLab identity verification до запуска задач (0 jobs).
Сопровождающий запустил
[pipeline #2910777765](https://gitlab.com/fdroid/fdroiddata/-/pipelines/2910777765),
все проверки прошли. Заявка принята 5 октября, `1.1` появилась в каталоге
6 октября 2026 года.

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

Сопровождающие F-Droid выполнили свою сборку, подпись и публикацию `1.1`.
Версию обновления следует проверять на странице приложения в каталоге.
