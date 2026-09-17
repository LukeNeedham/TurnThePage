# Сборки release и benchmark

Версия приложения задаётся в `gradle.properties` (`listai.versionName` и
`listai.versionCode`). Для текущего кандидата RC2 это `1.0.0-rc2` и `12`.
Упаковка release не меняет эти значения.

## Повторяемая локальная сборка

Из корня репозитория, используя тот же JDK и Android SDK, что и CI:

```sh
./gradlew :app:assembleRelease :app:bundleRelease
./gradlew :app:assembleBenchmark
```

`release` не отлаживается и остаётся неподписанным, если не переданы все четыре
внешних значения подписи. `benchmark` — похожий на release, неотлаживаемый и
profileable-вариант для эмулятора; он использует существующий debug-ключ и не
является артефактом для магазина.

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
