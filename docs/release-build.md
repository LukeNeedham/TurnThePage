# Сборки release и benchmark

Версия приложения задаётся в `gradle.properties` (`listai.versionName` и
`listai.versionCode`). Упаковка release не меняет эти значения.

## Повторяемая локальная сборка

Из корня репозитория, используя тот же JDK и Android SDK, что и CI:

```sh
./gradlew :app:assembleRelease :app:bundleRelease
./gradlew :app:assembleBenchmark
bash .sourcecraft/check-r8.sh release benchmark
```

`release` не отлаживается и остаётся неподписанным, если не переданы все четыре
внешних значения подписи. `benchmark` — похожий на release, неотлаживаемый и
profileable-вариант для эмулятора; он использует существующий debug-ключ и не
является артефактом для магазина.

## Обязательная оптимизация

Каждая release-сборка APK/AAB для SourceCraft, F-Droid, RuStore или другого канала
всегда использует полную оптимизацию R8: удаление неиспользуемого кода,
оптимизацию, обфускацию, full mode и optimized resource shrinking. Настройки
`isMinifyEnabled` и `isShrinkResources` включены, используется
`proguard-android-optimize.txt`; `benchmark` наследует конфигурацию release.
Не добавлять отключающие эти этапы флаги, широкие project keep/dontwarn или
`-ignorewarnings`. Если обнаружен runtime-контракт, добавить только необходимое
точечное правило и проверить сценарий на минифицированном APK.

Оба CI после сборки запускают `.sourcecraft/check-r8.sh release benchmark`:
проверка требует отчёты R8 и resource shrinking и отклоняет глобальное отключение
shrinking, optimization или obfuscation. CI сохраняет
`app/build/outputs/mapping/release/` и `app/build/outputs/mapping/benchmark/`.

`release.py build` автоматически проверяет R8 и сохраняет весь каталог
`app/build/outputs/mapping/release/` в `out/release/listai-<version>-r8/<apkSHA256>/`
рядом с APK. Каталог выбирается по SHA-256 подписанного APK, поэтому повторные
сборки той же версии сохраняют прежние отчёты. При ручной сборке выполнить
`bash .sourcecraft/check-r8.sh release`
и сохранить отчёты до следующей сборки. Особенно нужен
`mapping.txt`: он соответствует конкретному APK и используется для retrace падений.
Mapping предыдущего выпуска нельзя заменять mapping следующей сборки.

Проверять готовый минифицированный APK на эмуляторах API 26 и 36: холодный запуск,
импорт и чтение FB2/ZIP/EPUB/PDF, передачу книги через FileProvider, сохранение
настроек, закладок и позиции после остановки процесса и повторного запуска.
На API 26 проверить корректный PDF после отказа в импорте некорректного или
зашифрованного PDF, чтобы пройти путь изолированной службы. При обновлении
существующей установки сохранить библиотеку и прогресс; debug-тесты сами по себе
не подтверждают работоспособность оптимизированного release APK.

## Необязательная подпись release

Передайте keystore вне репозитория через свойства Gradle или переменные среды.
Свойства Gradle имеют приоритет:

| Свойство Gradle | Переменная среды |
| --- | --- |
| `listai.release.storeFile` | `LISTAI_RELEASE_STORE_FILE` |
| `listai.release.storePassword` | `LISTAI_RELEASE_STORE_PASSWORD` |
| `listai.release.keyAlias` | `LISTAI_RELEASE_KEY_ALIAS` |
| `listai.release.keyPassword` | `LISTAI_RELEASE_KEY_PASSWORD` |

Все четыре значения нужны вместе. В CI передавайте их как masked secrets, а
keystore записывайте во временный путь; не коммитьте файл, `keystore.properties`,
пароли или ключи. Без credentials `assembleRelease` и `bundleRelease` остаются
полезными проверками неподписанной упаковки.

Перед загрузкой в магазин владелец релиза должен отдельно подготовить production
keystore, alias, пароли и credentials выбранного канала распространения.
