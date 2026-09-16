# Подготовка релиза в SourceCraft

Версия: `0.8.1` (`versionCode=9`), предполагаемый тег: `v0.8.1`.
Исходный коммит приложения: `b2b98af`.

## Перед публикацией

Нужны адрес репозитория SourceCraft и доступ на запись, а для установочного APK —
постоянный ключ подписи приложения. Репозиторий и его видимость выбирает владелец.
В проекте пока нет файла лицензии; условия распространения исходников также
должны быть выбраны владельцем до объявления проекта открытым ПО.

Release APK из `assembleRelease` сейчас **не подписан**. Его нельзя использовать
как установочный файл. Ключ, пароли и токены не добавляются в Git, архив исходников
или ресурсы релиза. Для уже распространявшейся версии нужен прежний ключ.

## Сборка и проверки

Требуются JDK 17+, Android SDK Platform 36, Build Tools 36.0.0 и Gradle Wrapper.
Из корня репозитория:

```bash
./gradlew --console=plain :app:assembleRelease test lintRelease \
  :feature:reader:validateDebugScreenshotTest \
  :feature:library:validateDebugScreenshotTest
```

Проверить `app/build/outputs/apk/release/output-metadata.json`: пакет
`com.avstaim.listai`, версия `0.8.1`, код `9`.
Instrumentation выполняется отдельно на подключённом Android:

```bash
./gradlew connectedDebugAndroidTest
```

## Подпись

Следующий пример использует существующий keystore. Задайте переменные
`LISTAI_KEYSTORE` (абсолютный путь), `LISTAI_KEY_ALIAS`, `LISTAI_STORE_PASSWORD`
и `LISTAI_KEY_PASSWORD` в локальном окружении. Пароли не передавайте аргументами
команд и не сохраняйте в репозитории. Выполняйте команды из корня проекта в Bash.

```bash
set -euo pipefail
: "${ANDROID_HOME:?Укажите путь к Android SDK}"
: "${LISTAI_KEYSTORE:?Укажите путь к постоянному keystore}"
: "${LISTAI_KEY_ALIAS:?Укажите alias ключа}"
: "${LISTAI_STORE_PASSWORD:?Задайте пароль keystore в окружении}"
: "${LISTAI_KEY_PASSWORD:?Задайте пароль ключа в окружении}"
export LISTAI_STORE_PASSWORD LISTAI_KEY_PASSWORD
android_build_tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p out/release
"$android_build_tools/zipalign" -P 16 -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  out/release/listai-0.8.1-aligned.apk
"$android_build_tools/apksigner" sign \
  --ks "$LISTAI_KEYSTORE" --ks-key-alias "$LISTAI_KEY_ALIAS" \
  --ks-pass env:LISTAI_STORE_PASSWORD --key-pass env:LISTAI_KEY_PASSWORD \
  --out out/release/listai-0.8.1.apk \
  out/release/listai-0.8.1-aligned.apk
"$android_build_tools/apksigner" verify --verbose --print-certs \
  out/release/listai-0.8.1.apk
"$android_build_tools/zipalign" -c -P 16 4 out/release/listai-0.8.1.apk
(cd out/release && sha256sum listai-0.8.1.apk > SHA256SUMS)
unset LISTAI_STORE_PASSWORD LISTAI_KEY_PASSWORD
```

Проверить установку и основные сценарии на тестовом устройстве, затем приложить
только подписанный `listai-0.8.1.apk` и соответствующий `SHA256SUMS`.

## Размещение

1. Подключить выбранный репозиторий SourceCraft как remote. Для первого переноса
   выбрать пустой репозиторий, без автоматически созданного README.
2. Перенести подготовленные изменения и отправить ветку с историей в репозиторий.
   Убедиться, что `.sourcecraft/ci.yaml` находится в основной ветке.
3. Дождаться успешного CI. Конфигурация запускает сборку, JVM-тесты, lint и проверку
   скриншотов на облачном worker. Установочный APK она не подписывает.
4. Создать тег `v0.8.1` на проверенном коммите. Если тег уже существует, сначала
   сверить его коммит; не перезаписывать тег опубликованного релиза.
5. В SourceCraft открыть «Код → Релизы → Новый релиз», выбрать тег, название
   «Листай 0.8.1» и описание из `docs/releases/0.8.1.md`.
6. Приложить подписанный APK и `SHA256SUMS`, сохранить черновик, проверить его
   содержимое и затем опубликовать.

Альтернатива для авторизованного SourceCraft CLI, после отправки тега:

```bash
# Заменить owner/listai на реальное имя организации/репозитория.
src release create -R owner/listai --tag v0.8.1 --title "Листай 0.8.1" \
  --release-notes "$(cat docs/releases/0.8.1.md)"
src release upload v0.8.1 out/release/listai-0.8.1.apk -R owner/listai
src release upload v0.8.1 out/release/SHA256SUMS -R owner/listai
src release get v0.8.1 -R owner/listai
# Выполнить после проверки готового черновика:
src release publish v0.8.1 -R owner/listai
```

## Ограничения проверки

Конфигурация SourceCraft подготовлена по официальной документации; до первого
прогона в целевом репозитории её работа на облачном worker не подтверждена.
Она собирает instrumentation APK, но не запускает эмулятор. Проверка установки
именно подписанного release APK выполняется после предоставления ключа.

## Официальные справочники

- [Релизы SourceCraft](https://sourcecraft.dev/portal/docs/ru/sourcecraft/operations/releases-work)
- [Создание релиза через CLI](https://sourcecraft.dev/portal/docs/ru/cli-ref/src-release-create)
- [Загрузка ресурсов](https://sourcecraft.dev/portal/docs/en/cli-ref/src-release-upload)
- [Синтаксис CI/CD](https://sourcecraft.dev/portal/docs/en/sourcecraft/ci-cd-ref/)
- [GitHub Actions в SourceCraft](https://sourcecraft.dev/portal/docs/en/sourcecraft/concepts/gh-actions)
- [Подпись APK](https://developer.android.com/tools/apksigner)
