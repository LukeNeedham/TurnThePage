---
name: listai-release
description: Выпуск версий Android-приложения Листай в SourceCraft — проверка версии, сборка и подпись постоянным ключом, push, CI, тег и публикация APK. Использовать при запросах выложить, выпустить или обновить релиз Листай.
---

# Выпуск Листай в SourceCraft

## Постоянные настройки

- Публичный репозиторий: https://sourcecraft.dev/avstaim/listai, `avstaim/listai`.
- Основная ветка `main`; remote `origin`: `https://git@git.sourcecraft.dev/avstaim/listai.git`.
- Версия и код: `listai.versionName` / `listai.versionCode` в `gradle.properties`.
- Пакет `com.avstaim.listai`; тег `v<versionName>` (например, `v0.9`, без добавления `.0`).
- CLI: `~/.local/bin/src`; сохранённая IAM-авторизация: `~/.config/sourcecraft`.
  Вызывай CLI через `bash .agents/skills/listai-release/scripts/sourcecraft.sh ...`.
- Постоянный ключ: `~/.local/share/listai/signing/listai-release.p12`, alias `listai-release`;
  пароль: `~/.local/share/listai/signing/keystore-password.txt`.
  Скрипт сборки читает пароль сам и передаёт Gradle через окружение.
- SHA-256 сертификата выпущенной `0.8.1`:
  `3a3154d0b98e97a93182f399539d03a96701c8319ec4d88833c0c23a090e81a8`.
  Следующие версии подписываются этим же ключом. Новый автоматически не создавать.

Пути относятся к настроенной машине. На другой машине нужны локальная авторизация
и защищённая копия того же ключа. Токены, пароль и keystore не входят в Git или релиз.

## Проверка перед выпуском

Команды выполнять из корня текущего checkout, включая worktree:

```bash
bash .agents/skills/listai-release/scripts/sourcecraft.sh auth status
python3 .agents/skills/listai-release/scripts/release.py check
git status --short
git remote -v
git fetch origin --tags
git log --oneline --left-right HEAD...origin/main
```

Не проси URL или новый вход, пока не проверен сохранённый доступ. Ошибки sandbox,
сети или записи в `~/.config/sourcecraft` требуют разрешённого запуска команды,
а не повторной авторизации. Только при сообщении CLI об истёкшем/отсутствующем входе
используй `auth login`; не утверждай, что браузер открыт у пользователя, если он
открылся лишь на машине исполнения.

Если в новом checkout нет `origin`, добавь URL выше. Другой существующий remote не заменять.
Основной проект уже имеет локальный Git credential helper. Если helper нужен новому
checkout, его значение для `credential.https://git.sourcecraft.dev.helper`:
`!env XDG_CONFIG_HOME=<user-home>/.config <user-home>/.local/bin/src auth git-credential`.
Подставляй настоящий домашний каталог с shell-экранированием. Настраивай helper локально,
без токенов в URL и без изменения настроек других хостов.

Сверь `src release list -R avstaim/listai`, теги и версию APK. Уже подготовленную
пользователем версию не повышай повторно; новый versionCode должен быть больше предыдущего.
При расхождении историй сохрани обе ветви обычным merge с актуальной версией приложения.
Не используй force-push и не включай посторонние незакоммиченные изменения в выпуск.

## Сборка и подпись

При настроенных JDK/Android SDK:

```bash
./gradlew --console=plain test lintRelease \
  :feature:reader:validateDebugScreenshotTest \
  :feature:library:validateDebugScreenshotTest
python3 .agents/skills/listai-release/scripts/release.py build
```

`build` использует opt-in signing из `app/build.gradle.kts`, отключает configuration
cache для сборки с паролем, проверяет сертификат, пакет, версию, код версии и zipalign.
Каждый release всегда использует полную оптимизацию R8 и ресурсов; `build` удаляет
старые отчёты R8 перед сборкой и выполняет `.sourcecraft/check-r8.sh release` после неё.
Не отключать shrinking, optimization, obfuscation, full mode или optimized resource
shrinking; не добавлять широкие project keep/dontwarn ради прохождения сборки.
Результат: `out/release/listai-<version>.apk`, `.apk.sha256` и каталог
`out/release/listai-<version>-r8/<apkSHA256>/` с mapping и отчётами именно этого APK.
Повторные сборки версии сохраняют отдельные каталоги по SHA-256 подписанного APK;
существующие отчёты не заменяются. Сохранять их вместе с APK для разбора падений.
Проверка готового APK без изменения файлов: `release.py verify --apk <path>`.
Переопределения путей перечислены в `release.py --help`; пароли в аргументы не вставлять.

Проверь установку и холодный запуск именно этого APK на отдельном чистом эмуляторе.
Debug подписан другим ключом; не удаляй установленное приложение с пользовательскими
данными ради теста. Сбой эмулятора отделяй от падения приложения и отмечай ограничения.

## Push и публикация

Запрос выпустить версию включает push и публикацию этой версии. При запросе только
подготовки или настройки выполняй только этот объём: навык сам не разрешает публикацию.

1. Подготовь `docs/releases/<version>.md` с пользовательскими изменениями. Позиционирование
   Листай: фолды и двустраничный разворот, настоящая бумажная книга, современный интерфейс.
   Не своди описание к поддержке FB2.
2. Зафиксируй изменения выпуска, запиши SHA и отправь нужную ветку. Для `main`:
   `git push origin HEAD:main` после проверки истории.
3. Дождись успешного CI для **этого коммита** через `src run list` / `src run get <run>`
   с `-R avstaim/listai --json`. Логи:
   `src api repos/avstaim/listai/cicd/logs/<run>/android-quality/android-host/<cube>`.
   Учитывай страницы логов (`page_complete`, `done`). Native CI устанавливает JDK/SDK через
   `.sourcecraft/setup-android.sh`, передаёт пути через `SOURCECRAFT_ENV`; отдельные setup
   GitHub Actions ранее теряли окружение.
4. Создай аннотированный тег `v<version>` на проверенном SHA и отправь его. Для существующего
   тега сверь SHA; опубликованные теги не передвигать. Проверь наличие релиза командой
   `src release get v<version> -R avstaim/listai` перед изменением состояния.
5. Создай черновик: `src release create -R avstaim/listai --tag v<version> --title "Листай <version>" --release-notes <text>`.
   Не добавляй `--target-branch`: тег уже закреплён. Текст прочитай из файла и передай
   отдельным аргументом `subprocess.run`, сохраняя переносы.
6. Загрузи подписанный APK и его `.apk.sha256`:
   `src release upload v<version> <file> -R avstaim/listai`.
   Для существующего черновика сначала сверь тег и ресурсы; при таймауте проверь состояние
   до повтора. Другие ресурсы не удалять и не заменять молча.
7. Проверь состав и SHA, затем `src release publish v<version> -R avstaim/listai`.
   Через `release get` подтверди `published`, совпадение hash и наличие обоих файлов.
   Повторный запуск не должен создавать дубликаты или менять опубликованный релиз.

В отчёте дай ссылку, версию, итог CI, путь к APK и ограничения проверки. Пароль и ключ
не выводи. Подтверждай публикацию только по ответу SourceCraft.
